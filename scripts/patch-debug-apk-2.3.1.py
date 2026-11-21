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
DEX = {"classes9.dex": "classes.dex", "classes11.dex": "classes2.dex",
       "classes6.dex": "classes3.dex", "classes4.dex": "classes4.dex"}
CLIENT = "Lcom/google/antigravity/network/DirectOpenRouterChatClient;"
FAILURE = "Lcom/google/antigravity/network/OpenRouterChatFailure;"
JSON = "Lorg/json/JSONObject;"
CHECK_CHOICE = f"{CLIENT}->throwChoiceFailure({JSON})V"
MODELS = "Lcom/google/antigravity/model/AntigravityModelsKt;"
OPTION = "Lcom/google/antigravity/model/AntigravityModelOption;"
ROUTE = "Lcom/google/antigravity/model/PhoneModelRoute;"
MODERN = f"{MODELS}->isPublicGeminiModel(Ljava/lang/String;)Z"
MIGRATE = f"{MODELS}->mapToDirectGeminiModelId(Ljava/lang/String;)Ljava/lang/String;"
MERGE = f"{MODELS}->addDirectGeminiOptions(Ljava/util/List;)Ljava/util/List;"
DIRECT_GEMINI = (
    ("gemini-4-hydrogen", "Gemini 4 Hydrogen", "Gemini API • Есть бесплатный тариф"),
    ("gemini-4-neon", "Gemini 4 Neon", "Gemini API • Платный тариф"),
    ("gemini-4-carbon", "Gemini 4 Carbon", "Gemini API • Платный тариф"),
    ("gemini-4-argon", "Gemini 4 Argon", "Gemini API • Платный тариф"),
    ("gemini-3.8-flash", "Gemini 3.8 Flash", "Gemini API"),
    ("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Gemini API"),
)


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


def patch_gemini(decoded):
    model_dir = decoded / "smali_classes3/com/google/antigravity/model"
    models = model_dir / "AntigravityModelsKt.smali"
    replace_once(models, f"    sput-object v0, {MODELS}->AVAILABLE_AGY_MODELS:Ljava/util/List;",
                 f"    invoke-static {{v0}}, {MERGE}\n    move-result-object v0\n"
                 f"    sput-object v0, {MODELS}->AVAILABLE_AGY_MODELS:Ljava/util/List;")
    replace_once(models, '    move-result-object v0\n\n    return-object v0\n.end method\n\n.method public static final mcpPermissionSourcePriority',
                 '''    move-result-object v0
    const-string v1, "gemini-2.5-pro"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :check_legacy_flash
    const-string v0, "gemini-3.8-flash"
    return-object v0

    :check_legacy_flash
    const-string v1, "gemini-2.5-flash"
    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :current_model
    const-string v0, "gemini-3.5-flash-lite"

    :current_model
    return-object v0
.end method

.method public static final mcpPermissionSourcePriority''')

    with models.open("a", encoding="utf-8") as out:
        out.write("\n.method public static isPublicGeminiModel(Ljava/lang/String;)Z\n    .locals 1\n")
        for model, _, _ in DIRECT_GEMINI:
            out.write(f'''    const-string v0, "{model}"
    invoke-virtual {{v0, p0}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v0
    if-nez v0, :public_model
''')
        out.write('''    const/4 v0, 0x0
    return v0

    :public_model
    const/4 v0, 0x1
    return v0
.end method
''')
        out.write(f'''
.method public static addDirectGeminiOptions(Ljava/util/List;)Ljava/util/List;
    .locals 5
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {{v0}}, Ljava/util/ArrayList;-><init>()V
    invoke-interface {{p0}}, Ljava/util/List;->iterator()Ljava/util/Iterator;
    move-result-object v1

    :next_model
    invoke-interface {{v1}}, Ljava/util/Iterator;->hasNext()Z
    move-result v2
    if-eqz v2, :add_direct
    invoke-interface {{v1}}, Ljava/util/Iterator;->next()Ljava/lang/Object;
    move-result-object v2
    check-cast v2, {OPTION}
    invoke-virtual {{v2}}, {OPTION}->getId()Ljava/lang/String;
    move-result-object v3
    invoke-static {{v3}}, {MIGRATE}
    move-result-object v4
    invoke-virtual {{v3, v4}}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v3
    if-eqz v3, :next_model
    invoke-virtual {{v0, v2}}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    goto :next_model

    :add_direct
''')
        for model, title, badge in DIRECT_GEMINI:
            out.write(f'''    new-instance v1, {OPTION}
    const-string v2, "{model}"
    const-string v3, "{title}"
    const-string v4, "{badge}"
    invoke-direct {{v1, v2, v3, v4}}, {OPTION}-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    invoke-virtual {{v0, v1}}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
''')
        out.write("    return-object v0\n.end method\n")

    picker = model_dir / "ModelPickerOptionsKt.smali"
    replace_once(picker, "    :cond_0\n    check-cast v0, Ljava/lang/Iterable;",
                 f"    :cond_0\n    check-cast v0, Ljava/util/List;\n    invoke-static {{v0}}, {MERGE}\n"
                 "    move-result-object v0\n    check-cast v0, Ljava/lang/Iterable;")
    route = model_dir / "PhoneModelRouteKt.smali"
    replace_once(route, '    .line 75\n    const-string v3, "gemini-4-"',
                 f"    invoke-static {{v2}}, {MODERN}\n    move-result v3\n    if-eqz v3, :non_public_route\n"
                 f"    sget-object v3, {ROUTE}->GEMINI_API:{ROUTE}\n    return-object v3\n"
                 '    :non_public_route\n    .line 75\n    const-string v3, "gemini-4-"')
    replace_once(route, '    .line 22\n    const-string v2, "gemini-4-"',
                 f"    invoke-static {{v1}}, {MODERN}\n    move-result v2\n    if-eqz v2, :non_public_id\n"
                 '    return-object v1\n    :non_public_id\n    .line 22\n    const-string v2, "gemini-4-"')
    replace_once(route, '    const-string v3, "gemini-2.5-pro"', '    const-string v3, "gemini-4-hydrogen"')
    replace_once(route, '    const-string v3, "gemini-2.5-flash"', '    const-string v3, "gemini-3.8-flash"')
    replace_once(route, "    move-object v3, v0\n\n    goto :goto_1\n\n    .line 22",
                 f"    invoke-static {{v0}}, {MIGRATE}\n    move-result-object v3\n\n    goto :goto_1\n\n    .line 22")

    view_model = decoded / "smali_classes4/com/google/antigravity/viewmodel/AntigravityViewModel.smali"
    replace_once(view_model, "    :cond_8\n    move-object/from16 v19, v0\n\n    .line 399",
                 f"    :cond_8\n    invoke-static {{v0}}, {MIGRATE}\n    move-result-object v0\n"
                 "    move-object/from16 v19, v0\n\n    .line 399")
    replace_once(view_model, "    .line 2487\n    iget-object v1, v0,",
                 f"    invoke-static {{v2}}, {MIGRATE}\n    move-result-object v2\n\n    .line 2487\n    iget-object v1, v0,")
    backend = decoded / "smali_classes2/com/google/antigravity/network/AntigravityBackendClient.smali"
    replace_once(backend, '    const-string v0, "gemini-2.5-flash"', '    const-string v0, "gemini-4-hydrogen"')


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
        patch_gemini(decoded)
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
