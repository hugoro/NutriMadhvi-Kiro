# Kiro Hooks

## on-spec-complete
When a spec task is marked complete, run the relevant unit tests to confirm nothing regressed.

```bash
./gradlew testDebugUnitTest --tests "*$(spec_name)*" 2>&1 | tail -20
```

## on-room-migration
Before generating any Room migration, check the current schema version:

```bash
grep -r "version = " app/src/main/java --include="*.kt" | grep "@Database"
```

Always increment version by 1 and add the migration to `AppDatabase.kt`'s `addMigrations()` call.
Never use `fallbackToDestructiveMigration()`.

## on-new-string
When adding any user-visible string, add it to the `tr()` map in the relevant composable with both `pt` and `gu` translations. English literal is the fallback — never the only value.

## pre-commit
```bash
# Verify no secrets are staged
git diff --cached --name-only | xargs grep -l "AIza\|sk-\|xai-\|gsk_" 2>/dev/null && echo "SECRET DETECTED — abort" && exit 1 || echo "Clean"
```
