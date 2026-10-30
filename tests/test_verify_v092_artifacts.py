import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

SOURCE = Path(__file__).resolve().parents[1] / 'scripts' / 'verify_v092_artifacts.py'
spec = importlib.util.spec_from_file_location('verifier', SOURCE)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class CheckRelease(unittest.TestCase):
    def test_actual_public_candidate(self):
        apk = Path(__file__).resolve().parents[1] / 'downloads/v0.9.2/Malty-signed-v0.9.2.apk'
        sums = Path(__file__).resolve().parents[1] / 'downloads/v0.9.2/SHA256SUMS.txt'
        result = verifier.run(apk, sums)
        self.assertTrue(result['verified'])
        self.assertGreater(result['zip_entries'], 1)

    def test_corruption_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            original=(Path(__file__).resolve().parents[1] / 'downloads/v0.9.2/Malty-signed-v0.9.2.apk').read_bytes()
            mutated=bytearray(original)
            mutated[-55] ^= 0x01
            apk=Path(tmp)/'modified.apk'
            apk.write_bytes(mutated)
            with self.assertRaisesRegex(ValueError, 'does not match'):
                verifier.run(apk, Path(__file__).resolve().parents[1] / 'downloads/v0.9.2/SHA256SUMS.txt')

    def test_wrong_manifest_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            sums=Path(tmp)/'SHA256SUMS.txt'
            sums.write_text('0'*64+'  Malty-signed-v0.9.2.apk\n')
            with self.assertRaisesRegex(ValueError, 'SHA256SUMS'):
                verifier.run(Path(__file__).resolve().parents[1] / 'downloads/v0.9.2/Malty-signed-v0.9.2.apk', sums)

    def test_private_database_in_apk_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'bad.apk'
            with zipfile.ZipFile(apk, 'w') as z:
                z.writestr('AndroidManifest.xml',b'x')
                z.writestr('classes.dex', b'x')
                z.writestr('assets/agent-rooms.db', b'x')
            with self.assertRaisesRegex(ValueError, 'private material'):
                verifier.validate_archive(apk)

    def test_zip_slip_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'bad.apk'
            with zipfile.ZipFile(apk,'w') as z:
                z.writestr('AndroidManifest.xml',b'x')
                z.writestr('classes.dex',b'x')
                z.writestr('../data.bin',b'x')
            with self.assertRaisesRegex(ValueError, 'Unsafe APK entry'):
                verifier.validate_archive(apk)

    def test_duplicate_entries_rejected(self):
        import warnings
        with tempfile.TemporaryDirectory() as tmp:
            apk=Path(tmp)/'bad.apk'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore',UserWarning)
                with zipfile.ZipFile(apk,'w') as z:
                    z.writestr('AndroidManifest.xml',b'x')
                    z.writestr('classes.dex',b'x')
                    z.writestr('classes.dex',b'x')
            with self.assertRaisesRegex(ValueError, 'duplicate entries'):
                verifier.validate_archive(apk)


if __name__=='__main__':
    unittest.main()
