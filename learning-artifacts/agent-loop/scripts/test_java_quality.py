"""Exercise Git scopes and the real Maven formatter/checker using disposable projects."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import java_quality


PROJECT = Path(__file__).resolve().parents[1]


class ScopeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT / "tmp", prefix="quality-scope-")
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.project = self.repository / "app"
        self.source = self.project / "src/main/java/example/Existing.java"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("package example;\nclass Existing {}\n")
        self.git("init", "-q")
        self.git("config", "user.name", "Quality Test")
        self.git("config", "user.email", "quality@example.invalid")
        self.git("add", ".")
        self.git("commit", "-qm", "initial fixture")
        self.git("tag", "baseline")

    def git(self, *arguments):
        subprocess.run(["git", "-C", str(self.repository), *arguments], check=True, capture_output=True)

    def test_clean_scope_is_explicitly_empty(self):
        self.assertEqual(java_quality.selected_files(self.project), [])

    def test_includes_staged_unstaged_and_untracked_sources(self):
        self.source.write_text("package example;\nclass Existing { int value; }\n")
        self.git("add", ".")
        new = self.source.with_name("New.java")
        new.write_text("package example;\nclass New {}\n")
        (self.project / "notes.md").write_text("documentation")
        self.assertEqual(java_quality.selected_files(self.project), sorted([self.source, new]))

    def test_deleted_and_ignored_files_are_not_passed_to_maven(self):
        self.source.unlink()
        (self.project / ".gitignore").write_text("src/main/java/example/Ignored.java\n")
        self.source.with_name("Ignored.java").write_text("class Ignored {}\n")
        self.assertEqual(java_quality.selected_files(self.project), [])

    def test_base_keeps_committed_branch_changes_in_scope(self):
        self.source.write_text("package example;\nclass Existing { int value; }\n")
        self.git("add", ".")
        self.git("commit", "-qm", "branch change")
        self.assertEqual(java_quality.selected_files(self.project), [])
        self.assertEqual(java_quality.selected_files(self.project, base="baseline"), [self.source])

    def test_missing_base_fails_instead_of_skipping(self):
        with self.assertRaises(subprocess.CalledProcessError):
            java_quality.selected_files(self.project, base="missing-ref")

    def test_test_sources_are_selected(self):
        test = self.project / "src/test/java/example/ExampleTest.java"
        test.parent.mkdir(parents=True)
        test.write_text("package example;\nclass ExampleTest {}\n")
        self.assertEqual(java_quality.selected_files(self.project), [test])

    def test_check_runs_both_tools_and_propagates_failure(self):
        with patch("java_quality.subprocess.run") as run:
            run.side_effect = [subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0)]
            self.assertEqual(java_quality.run_checks(self.project, [self.source], "check"), 1)
            self.assertEqual(run.call_count, 2)


class MavenConfigurationTests(unittest.TestCase):
    def test_format_round_trip_and_checker_agreement(self):
        with tempfile.TemporaryDirectory(dir=PROJECT / "tmp", prefix="quality-maven-") as temporary:
            work = Path(temporary)
            shutil.copytree(PROJECT / "config", work / "config")
            namespace = "http://maven.apache.org/POM/4.0.0"
            root = ET.parse(PROJECT / "pom.xml").getroot()
            plugins = root.find(f"{{{namespace}}}build/{{{namespace}}}plugins")
            for plugin in list(plugins):
                artifact = plugin.find(f"{{{namespace}}}artifactId").text
                if artifact not in ("spotless-maven-plugin", "maven-checkstyle-plugin"):
                    plugins.remove(plugin)
            for element in plugins.iter():
                element.tag = element.tag.split("}")[-1]
            plugin_xml = ET.tostring(plugins, encoding="unicode")
            (work / "pom.xml").write_text(
                '<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>'
                '<artifactId>quality-fixture</artifactId><version>1</version><build>'
                + plugin_xml + "</build></project>"
            )
            source = work / "src/main/java/example/FormatProbe.java"
            source.parent.mkdir(parents=True)
            source.write_text(
                'package example;\npublic final class FormatProbe{\n'
                'public String text(){return """\n'
                '        stable text\n'
                '        """;}\n'
                'public record Point(int x,int y){}\n'
                'public Runnable task(){return new Runnable(){\n'
                'public void run(){System.out.println(text());}\n'
                'public String toString(){return "probe";}\n'
                '};}\n'
                'public static void main(String[] args){\n'
                'FormatProbe probe=new FormatProbe();\n'
                'System.out.print(probe.text());System.out.print(new Point(1,2));\n'
                'System.out.print(probe.task());}\n}\n'
            )

            def semantic_output():
                subprocess.run(
                    ["javac", "--release", "17", "-d", str(work / "classes"), str(source)],
                    check=True, capture_output=True, text=True, timeout=30,
                )
                return subprocess.run(
                    ["java", "-cp", str(work / "classes"), "example.FormatProbe"],
                    check=True, capture_output=True, text=True, timeout=30,
                ).stdout

            original_output = semantic_output()

            def maven(*goals):
                result = subprocess.run(
                    ["mvn", "-B", *goals], cwd=work, capture_output=True, text=True, timeout=120
                )
                (work / "last-maven.log").write_text(result.stdout + result.stderr)
                return result

            self.assertNotEqual(maven("spotless:check").returncode, 0)
            applied = maven("spotless:apply")
            self.assertEqual(applied.returncode, 0, applied.stdout + applied.stderr)
            formatted = source.read_bytes()
            reapplied = maven("spotless:apply")
            self.assertEqual(reapplied.returncode, 0, reapplied.stdout + reapplied.stderr)
            self.assertEqual(source.read_bytes(), formatted, "Formatter must be idempotent")
            self.assertEqual(semantic_output(), original_output, "Formatting must preserve text block and record behavior")
            checked = maven("spotless:check", "checkstyle:check")
            self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
            source.write_text(source.read_text().replace('return "probe";', 'if (true) return "probe";\nreturn "other";'))
            rejected = maven("checkstyle:check")
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("NeedBraces", rejected.stdout)


if __name__ == "__main__":
    unittest.main()
