"""PC-side training script for the enemy detector (run on a computer with a GPU if possible).

Setup:   pip install ultralytics
Data:    collect screenshots with DATASET_COLLECT_INTERVAL_MS = 1500 (config.py), pull them from
         /sdcard/Android/data/com.redfinger.gamebot/files/dataset/, label the enemy boxes in
         Roboflow / LabelImg (YOLO format), then arrange as:

    dataset/
      images/train/*.jpg   images/val/*.jpg
      labels/train/*.txt   labels/val/*.txt

Result:  runs/detect/enemy/weights/best.onnx  ->  push to the phone as
         /sdcard/Android/data/com.redfinger.gamebot/files/enemy.onnx
"""
from pathlib import Path
from ultralytics import YOLO

IMGSZ = 320            # MUST equal ENEMY_INPUT_SIZE in RootHost.java (raise to 416/640 if enemies are small)
CLASSES = ["enemy"]    # add more classes if you label them; ENEMY_CLASS = -1 treats all as enemies

root = Path("dataset").resolve()
(root / "enemy.yaml").write_text(
    f"path: {root}\ntrain: images/train\nval: images/val\nnames:\n"
    + "".join(f"  {i}: {n}\n" for i, n in enumerate(CLASSES)),
    encoding="utf-8",
)

model = YOLO("yolov8n.pt")
model.train(data=str(root / "enemy.yaml"), imgsz=IMGSZ, epochs=100, batch=32,
            name="enemy", patience=30)

best = YOLO("runs/detect/enemy/weights/best.pt")
# opset 12 + static shapes is what OpenCV DNN parses reliably.
best.export(format="onnx", imgsz=IMGSZ, opset=12, simplify=True, dynamic=False)
print("Exported:", "runs/detect/enemy/weights/best.onnx")
