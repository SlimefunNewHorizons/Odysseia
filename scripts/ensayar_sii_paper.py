#!/usr/bin/env python3
"""Ensayo opt-in del componente SII en Paper, sin cargar Odysseia completo ni datos reales.

Ejecutar mediante build_seguro.sh 25. Requiere Paper y sus libraries/cache ya
disponibles; crea un servidor desechable en loopback, nunca usa staging vivo.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("paper", "candidate", "vault", "libraries", "cache", "report"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    args.report.unlink(missing_ok=True)  # Un fallo no debe dejar un PASS de una ejecucion anterior.
    repo = Path(__file__).resolve().parents[1]
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    source = repo / "src/runtime-test/java/org/metamechanists/odysseia/economy/SiiRuntimeProbe.java"
    report = {"ok": False, "candidate_sha256": sha(args.candidate), "paper_sha256": sha(args.paper),
              "vault_sha256": sha(args.vault), "scope": "SII component; fictional Vault; synthetic Player", "phases": []}
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    # Un directorio nuevo, sin posibilidad de apuntar al mundo/config de otro servidor.
    with tempfile.TemporaryDirectory(prefix="sii-paper-") as temporary:
        root = Path(temporary)
        for name in ("libraries", "cache"):
            shutil.copytree(getattr(args, name), root / name)
        shutil.copy2(args.paper, root / "paper.jar")
        plugins = root / "plugins"
        plugins.mkdir()
        classes = root / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(str(p) for p in [args.candidate, args.vault, *sorted((root / "libraries").rglob("*.jar"))])
        subprocess.run([str(java / "javac"), "-proc:none", "-cp", classpath,
                        "-d", str(classes), str(source)], check=True)
        # Conserva byte por byte las clases del candidato; solo sustituye descriptor y entrypoint.
        with zipfile.ZipFile(args.candidate) as original, zipfile.ZipFile(plugins / "SiiRuntimeProbe.jar", "w") as out:
            for info in original.infolist():
                if info.filename != "plugin.yml":
                    out.writestr(info, original.read(info))
            with zipfile.ZipFile(args.vault) as vault:
                for info in vault.infolist():
                    if info.filename.startswith("net/milkbowl/vault/economy/") and info.filename.endswith(".class"):
                        out.writestr(info, vault.read(info))
            for path in classes.rglob("*.class"):
                out.write(path, path.relative_to(classes).as_posix())
            out.writestr("plugin.yml", "name: SiiRuntimeProbe\nversion: '1'\napi-version: '26.2'\n"
                         "main: org.metamechanists.odysseia.economy.SiiRuntimeProbe\n")
        (root / "eula.txt").write_text("eula=true\n")
        (root / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=0\nonline-mode=true\nwhite-list=true\n"
            "enable-rcon=false\nenable-query=false\nview-distance=2\nsimulation-distance=2\n"
            "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n"
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
            '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],'
            '"biome":"minecraft:plains"}\n'
            "level-seed=1\nmax-tick-time=120000\n")
        for phase in ("normal", "crash-debit", "resume-debit", "crash-credit", "resume-credit"):
            log = root / (phase + ".log")
            with log.open("w") as output:
                process = subprocess.Popen([str(java / "java"), "-Xms256M", "-Xmx1536M",
                    "-XX:ActiveProcessorCount=2", "-Dpaper.disableChannelLimit=true",
                    "-Dsii.probe.phase=" + phase, "-jar", "paper.jar", "--nogui"],
                    cwd=root, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT)
                try:
                    code = process.wait(timeout=240)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                    raise RuntimeError("Paper timeout in " + phase)
            expected = 77 if phase.startswith("crash-") else 0
            marker = root / ("probe-" + phase + (".crash" if expected == 77 else ".ok"))
            content = log.read_text(errors="replace")
            result = {"phase": phase, "exit": code, "expected_exit": expected,
                      "done": "Done (" in content, "marker": marker.is_file(), "log_sha256": sha(log),
                      "error_lines": sum(" ERROR" in line for line in content.splitlines())}
            report["phases"].append(result)
            args.report.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(result), flush=True)
            if code != expected or not marker.is_file() or not result["done"]:
                print(content[-14000:])
                raise RuntimeError("Paper probe failed: " + phase)
        report["ok"] = True
        report["probe_sha256"] = sha(plugins / "SiiRuntimeProbe.jar")
        args.report.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
