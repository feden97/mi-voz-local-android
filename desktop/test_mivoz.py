import array
import ctypes
from pathlib import Path
import shutil
import tempfile
import unittest
import wave

from mivoz import CATALOG, NativeResult, Params, VARIANTS, merge_wavs, normalize_reference, split_text


def make_wav(path, frames, sample=1200):
    with wave.open(str(path), "wb") as output:
        output.setparams((1, 2, 24_000, 0, "NONE", "not compressed"))
        output.writeframes(array.array("h", [sample] * frames).tobytes())


class AudioAndBatchTests(unittest.TestCase):
    def test_split_keeps_words_and_abbreviations(self):
        text = "La Dra. Pérez dijo: ¿venís mañana? Sí, a las 12.30.\n\nLlevá las llaves y la yerba."
        blocks = split_text(text, 45)
        self.assertTrue(all(len(block) <= 45 for block in blocks))
        self.assertEqual(" ".join(text.split()), " ".join(blocks))
        self.assertTrue(any("Dra. Pérez" in block for block in blocks))

    def test_split_long_sentence_does_not_drop_words(self):
        text = " ".join(f"palabra{i}" for i in range(300))
        blocks = split_text(text, 100)
        self.assertEqual(text.split(), " ".join(blocks).split())
        self.assertTrue(all(len(block) <= 100 for block in blocks))

    def test_native_abi_layout(self):
        self.assertEqual(8, ctypes.sizeof(ctypes.c_void_p))
        self.assertEqual(64, ctypes.sizeof(Params))
        self.assertEqual(40, ctypes.sizeof(NativeResult))
        self.assertEqual(40, Params.instruction.offset)
        self.assertEqual(24, NativeResult.error_msg.offset)

    def test_model_catalog_has_all_sizes_and_precisions(self):
        self.assertEqual(6, len(VARIANTS))
        for variant, names in VARIANTS.items():
            self.assertIn("-base-", names[0])
            for name in names:
                self.assertEqual(64, len(CATALOG["files"][name]["sha256"]))
            if variant.endswith("bf16"):
                self.assertTrue(all("BF16" in name for name in names))

    def test_merge_pcm_pause_and_crossfade_lengths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a, b = root / "a.wav", root / "b.wav"
            make_wav(a, 100, 1000)
            make_wav(b, 200, -1000)
            merge_wavs([a, b], root / "pause.wav", pause_ms=10)
            with wave.open(str(root / "pause.wav")) as result:
                self.assertEqual(540, result.getnframes())
            merge_wavs([a, b], root / "fade.wav", pause_ms=0, crossfade_ms=4)
            with wave.open(str(root / "fade.wav")) as result:
                self.assertEqual(204, result.getnframes())
                samples = array.array("h")
                samples.frombytes(result.readframes(result.getnframes()))
                self.assertEqual(1000, samples[0])
                self.assertEqual(-1000, samples[-1])

    @unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg no está en PATH")
    def test_reference_conversion_and_duration_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.wav"
            make_wav(source, 24_000 * 3)
            duration = normalize_reference(source, root / "reference.wav")
            self.assertAlmostEqual(3.0, duration, places=3)
            with self.assertRaises(ValueError):
                normalize_reference(source, root / "short.wav", clip_seconds=1)


if __name__ == "__main__":
    unittest.main()
