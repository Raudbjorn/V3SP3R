import re
import sqlite3
import unittest
from pathlib import Path

class PreservingMigrationTest(unittest.TestCase):
    def test_feature_upgrade_preserves_existing_rows(self):
        source=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/vesper/flipper/data/database/FindingsDatabase.kt').read_text()
        db=sqlite3.connect(":memory:")
        db.execute('CREATE TABLE vuln_findings(id TEXT NOT NULL PRIMARY KEY, submittedAt INTEGER NOT NULL, target TEXT NOT NULL, vulnType TEXT NOT NULL, description TEXT NOT NULL, evidence TEXT NOT NULL, severity TEXT NOT NULL, complexity INTEGER NOT NULL, status TEXT NOT NULL, reproductionAttempts INTEGER NOT NULL, validatedAt INTEGER)')
        db.execute("INSERT INTO vuln_findings VALUES ('existing-record', 1, 'Ä-device', 'info_disclosure', 'observed', 'retained evidence', 'low', 1, 'confirmed', 2, 3)")
        for constant,column,expected in [('MIGRATION_SQL', 'validationNotesJson', '[]'), ('SESSION_MIGRATION_SQL', 'sessionId', '')]:
            sql=re.search(r'const val '+constant+r' = "([^"\n]+)"',source).group(1)
            db.execute(sql)
            self.assertEqual(expected,db.execute('SELECT '+column+' FROM vuln_findings WHERE id=?',('existing-record',)).fetchone()[0])
        self.assertEqual(1,db.execute("SELECT COUNT(*) FROM vuln_findings").fetchone()[0])
        self.assertEqual(('retained evidence', 'confirmed', 2, 3), db.execute("SELECT evidence,status,reproductionAttempts,validatedAt FROM vuln_findings").fetchone())
        # Legacy rows are preserved but cannot leak into an arbitrary new chat session.
        query = re.search(r'@Query\("(SELECT \* FROM vuln_findings WHERE sessionId[^"\n]+)"\)', source).group(1)
        self.assertEqual([], db.execute(query, {'sessionId':'new-session','severity':None,'status':None}).fetchall())

if __name__ == '__main__': unittest.main()
