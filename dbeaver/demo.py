#!/usr/bin/env python3
"""Open DBeaver with the local demonstration database on port 55461."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from build import configuration, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", type=Path, required=True)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--dbeaver-home", type=Path, default=Path("/Applications/DBeaver.app/Contents/Eclipse"))
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    platform = args.dbeaver_home.resolve()
    java_home = Path(os.environ["JAVA_HOME"])
    interpreter = args.python.absolute()
    run(interpreter, "-I", "-c", "import z3,sqlglot; print('Python/Z3 ready')")
    version = next(line.split(":", 1)[1].strip() for line in (here / "META-INF/MANIFEST.MF").read_text().splitlines()
                   if line.startswith("Bundle-Version:"))
    target = Path(tempfile.mkdtemp(prefix="demo-session-", dir=here / "target"))
    classes = target / "classes"
    classes.mkdir()
    classpath = os.pathsep.join([str(platform / "plugins/*"), str(here / "target/classes"),
                              str(here.parent / "demo/target/sql-demo.jar")])
    run(java_home / "bin/javac", "--release", "21", "-encoding", "UTF-8", "-cp", classpath,
        "-d", classes, here / "src/test/java/io/github/vaneshik/sqlantipatterns/dbeaver/WorkbenchDemo.java")
    fragment = target / "demo.jar"
    with zipfile.ZipFile(fragment, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n"
                         "Bundle-SymbolicName: io.github.vaneshik.sqlantipatterns.demo;singleton:=true\nBundle-Version: 0.1.0\n"
                         "Fragment-Host: io.github.vaneshik.sqlantipatterns.dbeaver\n"
                         "Require-Bundle: org.jkiss.dbeaver.registry,org.jkiss.dbeaver.core\n\n")
        archive.writestr("fragment.xml", '<?xml version="1.0"?><fragment><extension point="org.eclipse.ui.startup">'
                         '<startup class="io.github.vaneshik.sqlantipatterns.dbeaver.WorkbenchDemo"/>'
                         '</extension></fragment>')
        for file in classes.rglob("*.class"):
            archive.write(file, file.relative_to(classes))
    plugin = here / f"target/repository/plugins/io.github.vaneshik.sqlantipatterns.dbeaver_{version}.jar"
    config = target / "configuration"
    configuration(platform, config, [
        f"io.github.vaneshik.sqlantipatterns.dbeaver,{version},{plugin.as_uri()},4,false",
        f"io.github.vaneshik.sqlantipatterns.demo,0.1.0,{fragment.as_uri()},4,false",
    ])
    home = target / "home"
    home.mkdir()
    preferences = target / "workspace/.metadata/.plugins/org.eclipse.core.runtime/.settings"
    preferences.mkdir(parents=True)
    (preferences / "org.jkiss.dbeaver.ui.statistics.prefs").write_text(
        "eclipse.preferences.version=1\nfeature.tracking.skipConfirmation=true\nfeature.tracking.enabled=false\n")
    (preferences / "org.jkiss.dbeaver.core.prefs").write_text(
        "eclipse.preferences.version=1\nsample.database.canceled=true\norg.jkiss.dbeaver.core.confirm.exit=never\n")
    launcher = next((platform / "plugins").glob("org.eclipse.equinox.launcher_*.jar"))
    environment = dict(os.environ)
    environment["PATH"] = str(interpreter.parent) + os.pathsep + environment["PATH"]
    with (target / "workbench.log").open("w") as log:
        process = subprocess.Popen([str(part) for part in [java_home / "bin/java", "-XstartOnFirstThread",
            f"-Duser.home={home}", "-Ddbeaver.debug.skip-version-check=true",
            f"-Dvkr.demo.driver={args.driver.resolve()}", f"-Dvkr.demo.result={target / 'result.txt'}",
            f"-Dvkr.demo.script={here / 'src/test/resources/demo.sql'}",
            "-jar", launcher, "-configuration", config, "-data", target / "workspace",
            "-application", "org.jkiss.dbeaver.ui.app.standalone.standalone",
            "-product", "org.jkiss.dbeaver.ui.app.standalone.product", "-nosplash", "-consoleLog"]],
            stdout=log, stderr=log, env=environment, start_new_session=True)
    (target / "pid").write_text(str(process.pid) + "\n")
    (here / "target/current-demo.txt").write_text(str(target) + "\n")
    print(target)
    print(f"DBeaver PID {process.pid}")


if __name__ == "__main__":
    main()
