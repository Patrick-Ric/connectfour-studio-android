# The app uses no reflection; R8 defaults are sufficient.
# Keep line numbers readable in crash reports without exposing source files.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
