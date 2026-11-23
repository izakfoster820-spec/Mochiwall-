# Keep enum names serialized via .name in ProjectJsonCodec
-keepclassmembers enum com.mochiwall.app.core.model.MochiPreset {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum com.mochiwall.app.core.model.PhysicsStyle {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
