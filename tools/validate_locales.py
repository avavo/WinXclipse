#!/usr/bin/env python3
"""Validate UI translations without building or packaging an APK."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

RES = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[-#+0,(<]*\d*(?:\.\d+)?[a-zA-Z%]")
# These arrays carry runtime identifiers, not display-only labels.
INVARIANT_ARRAYS = {
    "graphics_driver_entries", "wrapper_graphics_driver_version_entries",
    "audio_driver_entries", "dxwrapper_entries", "ddrawrapper_entries",
    "present_mode_entries", "resource_type_entries", "fexcore_preset_entries",
    "x87mode_preset_entries", "multiblock_values", "emulator_entries",
    "some_lc_all", "displayResolutionVariants", "userActionsValues",
    "forceOrientationVariants", "transformCapturedPointerValues",
    "touchscreenInputModesValues",
}


def resources(path):
    result = {}
    for element in ET.parse(path).getroot():
        if element.tag not in ("string", "string-array"):
            continue
        key = element.attrib["name"]
        assert key not in result, f"{path}: duplicate {key}"
        result[key] = element
    return result


def text(element):
    return "".join(element.itertext())


def placeholders(value):
    return Counter(token for token in FORMAT.findall(value) if token != "%%")


def main():
    base = resources(RES / "values/strings.xml")
    required = {key for key, node in base.items()
                if node.tag == "string" and node.get("translatable") != "false"}
    arrays = resources(RES / "values/arrays.xml")
    for language in ("pt", "es"):
        translated = resources(RES / f"values-{language}/strings.xml")
        assert required <= translated.keys(), f"{language}: missing {required - translated.keys()}"
        assert translated.keys() <= base.keys(), f"{language}: unknown string keys"
        for key, node in translated.items():
            assert text(node).strip(), f"{language}: empty {key}"
            assert placeholders(text(node)) == placeholders(text(base[key])), (
                f"{language}: incompatible format parameters in {key}")
        translated_arrays = resources(RES / f"values-{language}/arrays.xml")
        assert not INVARIANT_ARRAYS.intersection(translated_arrays), (
            f"{language}: runtime identifiers must not be translated")
        for key, node in translated_arrays.items():
            assert key in arrays, f"{language}: unknown array {key}"
            assert len(node) == len(arrays[key]), f"{language}: changed array indices in {key}"
        print(f"{language}: {len(translated)} strings, {len(translated_arrays)} display arrays OK")
    locales = ET.parse(RES / "xml/locales_config.xml").getroot()
    assert [node.attrib["{http://schemas.android.com/apk/res/android}name"]
            for node in locales] == ["en", "pt", "es"]


if __name__ == "__main__":
    main()
