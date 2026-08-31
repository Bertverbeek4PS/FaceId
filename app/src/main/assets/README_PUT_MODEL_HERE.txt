Put facenet.tflite in this folder.

Where to get it
---------------
github.com/shubham0204/FaceRecognition_With_FaceNet_Android
  -> app/src/main/assets/facenet.tflite      (160px input, 128 numbers out)

Or from the same author's newer repo, which also ships a 512-dimension version:
github.com/shubham0204/OnDevice-Face-Recognition-Android
  -> app/src/main/assets/facenet.tflite
  -> app/src/main/assets/facenet_512.tflite

Any of them work. FaceEmbedder reads the input size and output size from the
model file itself, so you do not have to change any code — including if you
later swap in MobileFaceNet.tflite (112px input), which is smaller and faster
but slightly less accurate.

If you change model, tap "Reload people" once afterwards. The embedding cache
notices the new dimension and rebuilds itself automatically.

The file is around 5-25 MB depending on which one you pick. It stays on the
phone; nothing is ever uploaded.
