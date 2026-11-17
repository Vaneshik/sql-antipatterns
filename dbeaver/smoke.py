#!/usr/bin/env python3
"""Exercise the plugin in a separate DBeaver workspace against a disposable PostgreSQL database."""

import argparse
import os
from pathlib import Path
import tempfile
import zipfile
from build import configuration, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dbeaver-home", type=Path, default=Path("/Applications/DBeaver.app/Contents/Eclipse"))
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--port", required=True)
    parser.add_argument("--user", required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    target = Path(tempfile.mkdtemp(prefix="smoke-", dir=here / "target"))
    platform = args.dbeaver_home.resolve()
    java_home = Path(os.environ["JAVA_HOME"])
    classes = target / "classes"
    classes.mkdir()
    classpath = os.pathsep.join([str(platform / "plugins/*"), str(here / "target/classes"),
                              str(here.parent / "demo/target/sql-demo.jar")])
    run(java_home / "bin/javac", "--release", "21", "-encoding", "UTF-8", "-cp", classpath,
        "-d", classes, *sorted((here / "src/test/java").rglob("*.java")))
    fragment = target / "smoke.jar"
    with zipfile.ZipFile(fragment, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n"
                         "Bundle-SymbolicName: io.github.vaneshik.sqlantipatterns.smoke;singleton:=true\nBundle-Version: 0.1.0\n"
                         "Fragment-Host: io.github.vaneshik.sqlantipatterns.dbeaver\n"
                         "Require-Bundle: org.jkiss.dbeaver.registry,org.jkiss.dbeaver.core\n\n")
        archive.writestr("fragment.xml", '<?xml version="1.0"?><fragment><extension point="org.eclipse.ui.startup">'
                         '<startup class="io.github.vaneshik.sqlantipatterns.dbeaver.WorkbenchSmoke"/>'
                         '</extension></fragment>')
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes))
    plugin = here / "target/repository/plugins/io.github.vaneshik.sqlantipatterns.dbeaver_0.1.0.jar"
    config = target / "configuration"
    configuration(platform, config, [
        f"io.github.vaneshik.sqlantipatterns.dbeaver,0.1.0,{plugin.as_uri()},4,false",
        f"io.github.vaneshik.sqlantipatterns.smoke,0.1.0,{fragment.as_uri()},4,false",
    ])
    home = target / "home"
    home.mkdir()
    result = target / "result.txt"
    preferences = target / "workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
    preferences.mkdir(parents=True)
    (preferences / "org.jkiss.dbeaver.ui.statistics.prefs").write_text(
        "eclipse.preferences.version=1\nfeature.tracking.skipConfirmation=true\nfeature.tracking.enabled=false\n")
    launcher = next((platform / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    with (target / "workbench.log").open("w") as log:
        run(java_home / "bin/java", "-XstartOnFirstThread", f"-Duser.home={home}",
            f"-Dvkr.smoke.driver={args.driver.resolve()}", f"-Dvkr.smoke.port={args.port}",
            f"-Dvkr.smoke.user={args.user}", f"-Dvkr.smoke.result={result}",
            f"-Dvkr.smoke.url=jdbc:postgresql://127.0.0.1:{args.port}/postgres",
            "-jar", launcher, "-configuration", config, "-data", target / "workspace",
            "-application", "org.jkiss.dbeaver.ui.app.standalone.standalone",
            "-product", "org.jkiss.dbeaver.ui.app.standalone.product", "-nosplash", "-consoleLog",
            stdout=log, stderr=log, timeout=150)
    print(result.read_text())
    print(target)
    if not result.read_text().startswith("PASS"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
