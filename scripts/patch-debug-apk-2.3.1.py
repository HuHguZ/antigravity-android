"""Patch the supplied 2.3.1 Debug APK whose streaming code is absent from main."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile


INPUT_SHA256 = "6d9bb03a2909126ef7783b73183fca4b71f090c7a39af82e15d277be7a944d91"
DEX = {"classes9.dex": "classes.dex", "classes11.dex": "classes2.dex"}
CLIENT = "Lcom/google/antigravity/network/DirectOpenRouterChatClient;"
FAILURE = "Lcom/google/antigravity/network/OpenRouterChatFailure;"
JSON = "Lorg/json/JSONObject;"
CHECK_CHOICE = f"{CLIENT}->throwChoiceFailure({JSON})V"


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise ValueError(f"Unexpected code in {path.name}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_layout(directory):
    intrinsic_height = re.compile(
        r"    sget-object (v\d+), Landroidx/compose/foundation/layout/IntrinsicSize;->Min:.*\n\n"
        r"    invoke-static \{(v\d+), \1\}, Landroidx/compose/foundation/layout/IntrinsicKt;->height\(.*\n\n"
        r"    move-result-object \2\n\n"
    )
    for name, expected in (
        ("ConversationActivityKt$ConversationActivity$5$2.smali", 1),
        ("MarkdownRendererKt.smali", 1),
        ("MarkdownRendererKt$MarkdownTableView$1.smali", 2),
    ):
        path = directory / name
        text, count = intrinsic_height.subn("", path.read_text(encoding="utf-8"))
        if count != expected:
            raise ValueError(f"Unexpected intrinsic height calls in {name}: {count}")
        path.write_text(text, encoding="utf-8")


def patch_openrouter(directory):
    client = directory / "DirectOpenRouterChatClient.smali"
    replace_once(client, '    :goto_0\n    if-eqz v4, :cond_7\n\n    const-string v5, "message"',
                 f'    :goto_0\n    if-eqz v4, :cond_7\n\n    invoke-virtual {{v1, v4}}, {CHECK_CHOICE}\n\n'
                 '    const-string v5, "message"')
    with client.open("a", encoding="utf-8") as out:
        out.write(f'''
.method public final throwChoiceFailure({JSON})V
    .locals 3

    const-string v0, "error"
    invoke-virtual {{p1, v0}}, {JSON}->optJSONObject(Ljava/lang/String;){JSON}
    move-result-object v1
    const/16 v2, 502
    if-eqz v1, :check_finish

    const-string v0, "code"
    invoke-virtual {{v1, v0, v2}}, {JSON}->optInt(Ljava/lang/String;I)I
    move-result v2
    goto :fail

    :check_finish
    const-string v1, "finish_reason"
    invoke-virtual {{p1, v1}}, {JSON}->optString(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {{v0, v1}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :ok

    :fail
    invoke-direct {{p0, v2}}, {CLIENT}->failure(I){FAILURE}
    move-result-object v0
    throw v0

    :ok
    return-void
.end method
''')
    stream = directory / "DirectOpenRouterChatClient$stream$3$1$1.smali"
    replace_once(stream, '    :cond_d\n    const-string v2, "finish_reason"',
                 f'    :cond_d\n    iget-object v2, v7, {CLIENT[:-1]}$stream$3$1$1;->this$0:{CLIENT}\n\n'
                 f'    invoke-virtual {{v2, v1}}, {CHECK_CHOICE}\n\n'
                 '    const-string v2, "finish_reason"')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--apktool", type=Path, required=True, help="Apktool 3.0.3 JAR")
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--java", default="java", help="Java 17+ executable")
    parser.add_argument("--keystore", type=Path, default=Path.home() / ".android/debug.keystore")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists")
    with args.input.open("rb") as src:
        if hashlib.file_digest(src, "sha256").hexdigest() != INPUT_SHA256:
            parser.error("Input does not match the supported Debug APK")

    zipalign = args.build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    apksigner = args.build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    env = os.environ.copy()
    if Path(args.java).is_absolute():
        env["JAVA_HOME"] = str(Path(args.java).parent.parent)

    def run(*command):
        subprocess.run([str(arg) for arg in command], check=True, env=env)

    with TemporaryDirectory(prefix="antigravity-apk-") as temp, ZipFile(args.input) as original:
        work = Path(temp)
        decoded = work / "decoded"
        slice_apk = work / "slice.apk"
        with ZipFile(slice_apk, "w") as part:
            for name in ("AndroidManifest.xml", "resources.arsc"):
                part.writestr(name, original.read(name))
            for original_name, slice_name in DEX.items():
                part.writestr(slice_name, original.read(original_name))
        run(args.java, "-jar", args.apktool, "d", "-r", "--no-assets", "-j", "2", "-o", decoded, slice_apk)
        patch_layout(decoded / "smali/com/google/antigravity/ui/components")
        patch_openrouter(decoded / "smali_classes2/com/google/antigravity/network")
        rebuilt = work / "rebuilt.apk"
        run(args.java, "-jar", args.apktool, "b", "-j", "2", "-o", rebuilt, decoded)

        unsigned = work / "unsigned.apk"
        with ZipFile(rebuilt) as patch, ZipFile(unsigned, "w") as apk:
            for entry in original.infolist():
                name = entry.filename
                if re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", name, re.I):
                    continue
                apk.writestr(entry, patch.read(DEX[name]) if name in DEX else original.read(entry))

        aligned = work / "aligned.apk"
        signed = work / "signed.apk"
        run(zipalign, "-P", "16", "-f", "4", unsigned, aligned)
        run(apksigner, "sign", "--ks", args.keystore, "--ks-pass", "pass:android", "--out", signed, aligned)
        run(apksigner, "verify", signed)
        with args.output.open("xb") as out, signed.open("rb") as src:
            shutil.copyfileobj(src, out)


if __name__ == "__main__":
    main()
