"""Tests for library.yaml merging (common.apply_library) and the studio's file handling.

    tools/audio/.venv/bin/python -I tools/audio/test_library.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402


class ApplyLibraryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = common.load_manifest(library=None)
        cls.tmp = tempfile.TemporaryDirectory()
        cls.wav = Path(cls.tmp.name) / "take.wav"
        cls.wav.write_bytes(b"RIFF")  # only its existence is checked here

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def merge(self, *entries):
        return common.apply_library(self.manifest, list(entries))

    def entry(self, **kw):
        return {"file": str(self.wav), **kw}

    def test_a_replacement_becomes_a_source_override_and_keeps_the_schedule(self):
        merged, problems = self.merge(self.entry(id="ad_1", group="radio_tejo", replace=True))
        self.assertEqual([], problems)
        self.assertEqual(str(self.wav), merged["source"]["radio_tejo/ad_1"])
        self.assertEqual(self.manifest["stations"], merged["stations"])

    def test_the_original_manifest_is_not_changed(self):
        before = repr(self.manifest)
        self.merge(self.entry(id="fado_novo_1", group="music",
                              schedule=[{"station": "radio_tejo", "phase": "night", "list": "programs"}]))
        self.assertEqual(before, repr(self.manifest))

    def test_a_new_song_joins_music_and_every_place_it_names(self):
        merged, problems = self.merge(self.entry(id="fado_novo_1", group="music", schedule=[
            {"station": "radio_tejo", "phase": "night", "list": "programs"},
            {"station": "emissora_lusitana", "phase": "day", "list": "programs"}]))
        self.assertEqual([], problems)
        self.assertIn("fado_novo_1", merged["music"])
        stations = {s["id"]: s for s in merged["stations"]}
        self.assertEqual("fado_novo_1", stations["radio_tejo"]["phases"]["night"]["programs"][-1])
        self.assertEqual("fado_novo_1", stations["emissora_lusitana"]["phases"]["day"]["programs"][-1])
        self.assertEqual(str(self.wav), merged["source"]["music/fado_novo_1"])

    def test_a_first_interstitial_sets_a_spacing(self):
        merged, problems = self.merge(self.entry(id="interval_tejo", group="music", schedule=[
            {"station": "emissora_lusitana", "phase": "night", "list": "interstitials"}]))
        # Lusitana's night has no programmes, so the interstitial loops after the opening.
        self.assertEqual([], problems)
        night = {s["id"]: s for s in merged["stations"]}["emissora_lusitana"]["phases"]["night"]
        self.assertEqual(1, night["interstitial_every"])

    def test_a_new_station_clip_needs_a_known_prefix_and_its_own_station(self):
        _, problems = self.merge(self.entry(id="jingle_1", group="radio_tejo",
                                            schedule=[{"station": "radio_tejo", "phase": "day", "list": "interstitials"}]))
        self.assertTrue(any("must start with one of" in p for p in problems), problems)
        _, problems = self.merge(self.entry(id="ad_9", group="radio_tejo",
                                            schedule=[{"station": "emissora_lusitana", "phase": "day", "list": "interstitials"}]))
        self.assertTrue(any("can only be scheduled on radio_tejo" in p for p in problems), problems)
        merged, problems = self.merge(self.entry(id="ad_9", group="radio_tejo",
                                                 schedule=[{"station": "radio_tejo", "phase": "day", "list": "interstitials"}]))
        self.assertEqual([], problems)

    def test_music_ids_cannot_look_like_speech(self):
        _, problems = self.merge(self.entry(id="news_5", group="music",
                                            schedule=[{"station": "radio_tejo", "phase": "day", "list": "programs"}]))
        self.assertTrue(any("speech prefix" in p for p in problems), problems)

    def test_breaking_clips_go_in_the_breaking_list_only(self):
        merged, problems = self.merge(self.entry(id="breaking_2", group="radio_tejo",
                                                 schedule=[{"station": "radio_tejo", "list": "breaking"}]))
        self.assertEqual([], problems)
        self.assertIn("breaking_2", {s["id"]: s for s in merged["stations"]}["radio_tejo"]["breaking"])
        _, problems = self.merge(self.entry(id="breaking_2", group="radio_tejo",
                                            schedule=[{"station": "radio_tejo", "phase": "day", "list": "programs"}]))
        self.assertTrue(problems)
        _, problems = self.merge(self.entry(id="ad_9", group="radio_tejo",
                                            schedule=[{"station": "radio_tejo", "list": "breaking"}]))
        self.assertTrue(problems)

    def test_an_off_air_phase_is_refused(self):
        _, problems = self.merge(self.entry(id="news_9", group="radio_atlantico",
                                            schedule=[{"station": "radio_atlantico", "phase": "day", "list": "programs"}]))
        self.assertTrue(any("off the air" in p for p in problems), problems)

    def test_mistakes_are_reported(self):
        cases = [
            (self.entry(id="ad_1", group="radio_tejo", schedule=[{"station": "radio_tejo", "phase": "day", "list": "programs"}]), "already exists"),
            (self.entry(id="ad_77", group="radio_tejo", replace=True), "no such clip"),
            (self.entry(id="Ad 1", group="radio_tejo", replace=True), "lowercase"),
            (self.entry(id="ad_1", group="radio_nowhere", replace=True), "group must be"),
            ({"id": "ad_1", "group": "radio_tejo", "replace": True, "file": "recordings/missing.wav"}, "not found"),
            (self.entry(id="ad_9", group="radio_tejo"), "needs at least one place"),
            (self.entry(id="ad_9", group="radio_tejo", schedule=[{"station": "radio_tejo", "phase": "noon", "list": "programs"}]), "phase must be"),
            (self.entry(id="ad_9", group="radio_tejo", schedule=[{"station": "radio_tejo", "phase": "day", "list": "middle"}]), "list must be"),
            (self.entry(id="ad_1", group="radio_tejo", replace=True, schedule=[{"station": "radio_tejo", "phase": "day", "list": "programs"}]), "drop 'schedule'"),
        ]
        for entry, expected in cases:
            with self.subTest(expected=expected):
                _, problems = self.merge(entry)
                self.assertTrue(any(expected in p for p in problems), problems)
        _, problems = self.merge(self.entry(id="ad_1", group="radio_tejo", replace=True),
                                 self.entry(id="ad_1", group="radio_tejo", replace=True))
        self.assertTrue(any("listed twice" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main(verbosity=1)
