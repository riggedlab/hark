# ONNX Runtime 的 native 层通过 JNI 反向查找/构造这些 Java 类，不能被裁剪或改名
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
