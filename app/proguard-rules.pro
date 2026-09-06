# JSch resolves negotiated algorithms from its configuration table by class name. Keep the
# complete conventional core/JCE implementations used by SshEngine, plus the Bouncy Castle
# X25519/Ed25519 paths required on Android versions whose platform JCA does not provide them.
# Keeping these small adapter layers intact is deliberately conservative: R8 may not otherwise see
# methods reached through JSch's string-based algorithm registry.
-keep class com.jcraft.jsch.* { *; }
-keep class com.jcraft.jsch.jce.** { *; }
-keep class com.jcraft.jsch.bc.XDH { *; }
-keep class com.jcraft.jsch.bc.SignatureEdDSA { *; }
-keep class com.jcraft.jsch.bc.SignatureEd25519 { *; }
-keep class com.jcraft.jsch.bc.KeyPairGenEdDSA { *; }

# Optional desktop-only integrations referenced by JSch are intentionally absent on Android.
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
