# T01 patches

These patches replay the accepted T01a and T01b changes from baseline commit `d2038b8e0dc8c99fbdf2c754e88dec0a8078a725` (the master baseline). Apply them in order:

1. `git apply docs/patches/t01/T01a-changes.patch`
2. `git apply docs/patches/t01/T01b-incremental.patch`

Branch `t01` already contains the resulting changes; do not apply the patches again on this branch.

Validation: `MediaStoreCopySafetyTest` passed 29/29; the full Release unit suite had 114/115 passing, with the sole `RecycleBinManagerTest.atomicReadRestoresLegacyBackup` failure reproduced on the unmodified baseline (`JSONException: Value truncated of type java.lang.String cannot be converted to JSONArray`); `:app:assembleDebug` succeeded. No device MediaStore verification was run.