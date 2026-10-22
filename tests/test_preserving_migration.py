import re
import sqlite3
import unittest
from pathlib import Path

class PreservingMigrationTest(unittest.TestCase):
    def test_feature_upgrade_preserves_existing_rows(self):
        source=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/vesper/flipper/data/database/FindingsDatabase.kt').read_text()
        db=sqlite3.connect(":memory:")
        db.execute('CREATE TABLE vuln_findings(id TEXT PRIMARY KEY, submittedAt INTEGER, target TEXT, evidence TEXT)')
        db.execute("INSERT INTO vuln_findings(id) VALUES ('existing-record')")
        for constant,column,expected in [('MIGRATION_SQL', 'validationNotesJson', '[]')]:
            sql=re.search(r'const val '+constant+r' = "([^"\n]+)"',source).group(1)
            db.execute(sql)
            self.assertEqual(expected,db.execute('SELECT '+column+' FROM vuln_findings WHERE id=?',('existing-record',)).fetchone()[0])
        self.assertEqual(1,db.execute("SELECT COUNT(*) FROM vuln_findings").fetchone()[0])

if __name__ == '__main__': unittest.main()
