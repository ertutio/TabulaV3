import hashlib
import json
import os
from pathlib import Path
import random
import sys
from datetime import datetime, timezone
from PIL import Image, ImageDraw, __version__ as pillow_version

protocol = json.loads(Path("benchmark/protocol-v1.json").read_text())
cfg = protocol["dataset"]
if pillow_version != cfg["pillow"]:
    raise RuntimeError(f"Pillow version must be {cfg['pillow']}, got {pillow_version}")
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = []
for index in range(cfg["count"]):
    rng = random.Random(cfg["seed"] + index)
    image = Image.new("RGB", (cfg["width"], cfg["height"]))
    draw = ImageDraw.Draw(image)
    for y in range(0, cfg["height"], 48):
        color = (40 + y * 150 // cfg["height"], 80, 210 - y * 100 // cfg["height"])
        draw.rectangle((0, y, cfg["width"], y + 47), fill=color)
    for n in range(80):
        x, y = rng.randrange(cfg["width"]), rng.randrange(cfg["height"])
        draw.rectangle((x, y, min(x + 60, cfg["width"] - 1), min(y + 80, cfg["height"] - 1)),
                       fill=(rng.randrange(40, 240), rng.randrange(40, 240), rng.randrange(40, 240)))
    draw.text((40, 40), f"Tabula synthetic fixture {index:03}", fill="white", font_size=32)
    stamp = cfg["modifiedEpoch"] + index * 30
    exif = Image.Exif()
    exif[306] = datetime.fromtimestamp(stamp, timezone.utc).strftime("%Y:%m:%d %H:%M:%S")
    exif[36867] = exif[306]
    file = out / f"perf_fixture_{index:03}.jpg"
    image.save(file, "JPEG", quality=cfg["quality"], optimize=False, progressive=False, exif=exif)
    os.utime(file, (stamp, stamp))
    payload = file.read_bytes()
    files.append({"name": file.name, "sha256": hashlib.sha256(payload).hexdigest(),
                  "bytes": len(payload), "width": cfg["width"], "height": cfg["height"], "modifiedEpoch": stamp})
manifest = {"schemaVersion": 1, "pillowVersion": pillow_version, "count": len(files), "files": files}
manifest["datasetSHA256"] = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
(out / "dataset.json").write_text(json.dumps(manifest, indent=2))
print(json.dumps({"count": len(files), "datasetSHA256": manifest["datasetSHA256"]}))
