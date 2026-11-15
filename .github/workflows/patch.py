p = "android/app/src/main/AndroidManifest.xml"
s = open(p).read()
perm = """<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
    """
assert "<application" in s and "</application>" in s
s = s.replace("<application", perm + "<application", 1)
svc = '<service android:name="com.me.musicmover.AudioService" android:exported="false" android:foregroundServiceType="mediaPlayback"/>\n    '
s = s.replace("</application>", svc + "</application>", 1)
open(p, "w").write(s)
