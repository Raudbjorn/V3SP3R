import re
import sqlite3
import unittest
from pathlib import Path

class PreservingMigrationTest(unittest.TestCase):
    def test_feature_upgrade_preserves_existing_rows(self):
        source=(Path(__file__).resolve().parents[1]/'app/src/main/java/com/vesper/flipper/domain/ralph/CampaignStore.kt').read_text()
        db=sqlite3.connect(":memory:")
        db.execute('CREATE TABLE campaigns(id TEXT PRIMARY KEY, plan TEXT, state TEXT)')
        db.execute("INSERT INTO campaigns(id) VALUES ('existing-record')")
        for constant,column,expected in [('MIGRATION_1_2', 'approvalExpires', 0), ('MIGRATION_PHASE_START', 'phaseStarted', 0)]:
            sql=re.search(r'const val '+constant+r' = "([^"\n]+)"',source).group(1)
            db.execute(sql)
            self.assertEqual(expected,db.execute('SELECT '+column+' FROM campaigns WHERE id=?',('existing-record',)).fetchone()[0])
        self.assertEqual(1,db.execute("SELECT COUNT(*) FROM campaigns").fetchone()[0])

if __name__ == '__main__': unittest.main()
