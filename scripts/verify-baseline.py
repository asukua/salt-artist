"""Check an additive plugin package against a known-good .spmod baseline.

This reads ZIP resources only. It does not import plugin classes or load native code.
Example:
  python scripts/verify-baseline.py --baseline build/libs/plugin-dev.saltartist-0.3.0.spmod \
      --package build/libs/plugin-dev.saltartist-0.3.1.spmod
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile


CONTRACT_FIELDS = ("type", "entries", "entry_values", "default_value")
DESCRIPTION_FIELDS = ("title", "summary")
MISSING = object()
REGIONAL_BIOGRAPHY_RETIRED = frozenset({
    ("artist-page.json", key) for key in (
        "artist_visual_storefront", "artist_visual_language", "apple_music_storefronts",
        "preferred_artist_language", "show_other_artist_languages",
    )
})
REGIONAL_BIOGRAPHY_REPLACEMENTS = {
    ("artist-page.json", "artist_storefront_order"): {"type": "edittext", "default_value": "US,JP,CN"},
    ("artist-page.json", "prefer_simplified_chinese_biography"): {"type": "switch", "default_value": True},
}


def canonical(value):
    # JSON equality must distinguish true from 1, unlike Python's equality.
    return None if value is MISSING else json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"))


def settings_from(archive):
    candidates = [name for name in archive.namelist() if name.endswith("preference_config.json")]
    if len(candidates) != 1:
        raise ValueError("Expected one preference_config.json, found " + str(len(candidates)))
    document = json.loads(archive.read(candidates[0]).decode("utf-8-sig"))
    result = {}
    for group in document["configs"]:
        for preference in group["preferences"]:
            if "key" not in preference:
                continue
            # A key must still use the same backing config file after an update.
            scope = (group["config"], preference["key"])
            if scope in result:
                raise ValueError("Duplicate preference: " + "/".join(scope))
            result[scope] = preference
    return result


def font_resources(archive):
    return {
        name: hashlib.sha256(archive.read(name)).hexdigest()
        for name in archive.namelist()
        if not name.endswith("/") and (name.startswith("fonts/") or "/fonts/" in name)
    }


def top_level_classes(archive):
    return {
        name for name in archive.namelist()
        if name.endswith(".class") and "$" not in name
        and (name.startswith("dev/saltartist/") or name.startswith("classes/dev/saltartist/"))
    }


def setting_identity(scope):
    return {"config": scope[0], "key": scope[1]}


def verify(baseline_path, package_path, settings_revision=None):
    if settings_revision not in (None, "regional-biography"):
        raise ValueError("Unsupported settings revision: " + str(settings_revision))
    with zipfile.ZipFile(baseline_path) as baseline, zipfile.ZipFile(package_path) as package:
        before = settings_from(baseline)
        after = settings_from(package)
        retired = REGIONAL_BIOGRAPHY_RETIRED if settings_revision == "regional-biography" else frozenset()
        missing_settings = [setting_identity(scope) for scope in sorted(before.keys() - after.keys() - retired)]
        changed_settings = []
        description_changes = []
        for scope in sorted(before.keys() & after.keys()):
            old, new = before[scope], after[scope]
            changed = [field for field in CONTRACT_FIELDS if canonical(old.get(field, MISSING)) != canonical(new.get(field, MISSING))]
            described = [field for field in DESCRIPTION_FIELDS if canonical(old.get(field, MISSING)) != canonical(new.get(field, MISSING))]
            identity = {"config": scope[0], "key": scope[1]}
            if changed:
                changed_settings.append({**identity, "fields": changed})
            if described:
                description_changes.append({**identity, "fields": described})

        migration_errors = []
        allowed_migrations = []
        if settings_revision == "regional-biography":
            for scope in sorted(retired - before.keys()):
                migration_errors.append({**setting_identity(scope), "error": "retired_setting_absent_from_baseline"})
            retired_keys = {scope[1] for scope in retired}
            for scope in sorted(after):
                if scope[1] in retired_keys:
                    migration_errors.append({**setting_identity(scope), "error": "retired_setting_still_present"})
            for scope, expected in REGIONAL_BIOGRAPHY_REPLACEMENTS.items():
                actual = after.get(scope)
                if actual is None:
                    migration_errors.append({**setting_identity(scope), "error": "replacement_setting_missing"})
                    continue
                fields = [field for field in CONTRACT_FIELDS
                          if canonical(actual.get(field, MISSING)) != canonical(expected.get(field, MISSING))]
                if fields:
                    migration_errors.append({**setting_identity(scope), "error": "replacement_contract_changed", "fields": fields})
            allowed_migrations.append({
                "revision": settings_revision,
                "removed": [setting_identity(scope) for scope in sorted(retired)],
                "added": [setting_identity(scope) for scope in sorted(REGIONAL_BIOGRAPHY_REPLACEMENTS)],
                "verified": not migration_errors,
            })

        old_fonts, new_fonts = font_resources(baseline), font_resources(package)
        missing_fonts = sorted(old_fonts.keys() - new_fonts.keys())
        changed_fonts = sorted(name for name in old_fonts.keys() & new_fonts.keys() if old_fonts[name] != new_fonts[name])
        old_classes, new_classes = top_level_classes(baseline), top_level_classes(package)
        missing_classes = sorted(old_classes - new_classes)
        failures = (missing_settings, changed_settings, migration_errors, missing_fonts, changed_fonts, missing_classes)
        return {
            "passed": not any(failures),
            "baseline": str(baseline_path),
            "package": str(package_path),
            "checked": {"settings": len(before), "font_resources": len(old_fonts), "top_level_classes": len(old_classes)},
            "missing_settings": missing_settings,
            "changed_settings": changed_settings,
            "description_changes": description_changes,
            "allowed_migrations": allowed_migrations,
            "migration_errors": migration_errors,
            "missing_fonts": missing_fonts,
            "changed_fonts": changed_fonts,
            "missing_top_level_classes": missing_classes,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True, help="Known-good original .spmod")
    parser.add_argument("--package", type=Path, required=True, help="New additive .spmod to check")
    parser.add_argument("--settings-revision", choices=("regional-biography",),
                        help="Explicitly verify the approved replacement of five regional/biography settings")
    args = parser.parse_args()
    try:
        report = verify(args.baseline, args.package, args.settings_revision)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        report = {"passed": False, "error": str(error)}
    print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
