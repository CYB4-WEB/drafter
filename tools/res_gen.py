import os
R = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
F = {
"xml/locales_config.xml": """<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="ar" />
</locale-config>""",
"xml/file_paths.xml": """<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="lib" path="." />
    <cache-path name="cache" path="." />
    <files-path name="files" path="." />
</paths>""",
"xml/widget_upcoming.xml": """<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp" android:minHeight="110dp"
    android:targetCellWidth="4" android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:initialLayout="@layout/widget_upcoming"
    android:previewLayout="@layout/widget_upcoming"
    android:description="@string/widget_upcoming_desc"
    android:widgetCategory="home_screen" />""",
"xml/widget_quick.xml": """<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp" android:minHeight="50dp"
    android:targetCellWidth="4" android:targetCellHeight="1"
    android:resizeMode="horizontal"
    android:initialLayout="@layout/widget_quick"
    android:previewLayout="@layout/widget_quick"
    android:description="@string/widget_quick_desc"
    android:widgetCategory="home_screen" />""",
"values/themes.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Daftar" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:windowBackground">@color/bg</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>""",
"values/colors.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="bg">#F6F5F2</color>
    <color name="surface">#FFFFFF</color>
    <color name="ink">#1C1B19</color>
    <color name="muted">#6B6A66</color>
    <color name="accent">#2F5E8C</color>
    <color name="line">#E3E1DC</color>
    <color name="launcher_bg">#2F5E8C</color>
</resources>""",
"values-night/colors.xml": """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="bg">#151514</color>
    <color name="surface">#1F1F1D</color>
    <color name="ink">#ECEAE5</color>
    <color name="muted">#A09E98</color>
    <color name="accent">#8FB4DA</color>
    <color name="line">#33322F</color>
</resources>""",
"mipmap-anydpi-v26/ic_launcher.xml": """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
    <monochrome android:drawable="@drawable/ic_launcher_fg" />
</adaptive-icon>""",
"drawable/ic_launcher_fg.xml": """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M36,30h30a6,6 0,0 1,6 6v36a6,6 0,0 1,-6 6h-30a4,4 0,0 1,-4 -4v-40a4,4 0,0 1,4 -4z"/>
    <path android:fillColor="#2F5E8C" android:pathData="M38,30h4v48h-4z"/>
    <path android:fillColor="#2F5E8C" android:pathData="M48,42h18v3h-18zM48,50h18v3h-18zM48,58h12v3h-12z"/>
    <path android:fillColor="#E8A33D" android:pathData="M60,30h7v14l-3.5,-3 -3.5,3z"/>
</vector>""",
"drawable/ic_notify.xml": """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF" android:pathData="M7,3h10a2,2 0,0 1,2 2v14a2,2 0,0 1,-2 2H7a2,2 0,0 1,-2 -2V5a2,2 0,0 1,2 -2zM9,7v2h8V7zM9,11v2h8v-2zM9,15v2h5v-2z"/>
</vector>""",
"drawable/widget_bg.xml": """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/surface" />
    <corners android:radius="20dp" />
</shape>""",
"drawable/widget_chip.xml": """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/bg" />
    <corners android:radius="14dp" />
</shape>""",
"drawable/widget_bar.xml": """<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/accent" />
    <corners android:radius="2dp" />
</shape>""",
}

row = """
        <LinearLayout android:id="@+id/row{i}" android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal" android:paddingTop="6dp" android:paddingBottom="6dp" android:visibility="gone">
            <ImageView android:id="@+id/bar{i}" android:layout_width="4dp" android:layout_height="34dp"
                android:src="@drawable/widget_bar" android:layout_marginEnd="10dp" />
            <LinearLayout android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content" android:orientation="vertical">
                <TextView android:id="@+id/title{i}" android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:textColor="@color/ink" android:textSize="14sp" android:maxLines="1" android:ellipsize="end" android:textStyle="bold" />
                <TextView android:id="@+id/sub{i}" android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:textColor="@color/muted" android:textSize="12sp" android:maxLines="1" android:ellipsize="end" />
            </LinearLayout>
        </LinearLayout>"""

F["layout/widget_upcoming.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root" android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@drawable/widget_bg" android:orientation="vertical" android:padding="14dp">
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal" android:gravity="center_vertical">
        <TextView android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:text="@string/widget_upcoming" android:textColor="@color/ink" android:textSize="16sp" android:textStyle="bold" />
        <TextView android:id="@+id/add" android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="@string/add_short" android:textColor="@color/accent" android:textSize="14sp" android:textStyle="bold"
            android:background="@drawable/widget_chip" android:paddingStart="12dp" android:paddingEnd="12dp" android:paddingTop="6dp" android:paddingBottom="6dp" />
    </LinearLayout>
    <TextView android:id="@+id/empty" android:layout_width="match_parent" android:layout_height="wrap_content"
        android:text="@string/nothing_upcoming" android:textColor="@color/muted" android:textSize="13sp" android:paddingTop="10dp" />
    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" android:paddingTop="6dp">""" + "".join(row.format(i=i) for i in range(4)) + """
    </LinearLayout>
</LinearLayout>"""

def chip(id, text):
    return f"""
    <TextView android:id="@+id/{id}" android:layout_width="0dp" android:layout_weight="1" android:layout_height="match_parent"
        android:gravity="center" android:text="@string/{text}" android:textColor="@color/ink" android:textSize="13sp" android:maxLines="1"
        android:background="@drawable/widget_chip" android:layout_marginStart="3dp" android:layout_marginEnd="3dp" />"""

F["layout/widget_quick.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@drawable/widget_bg" android:orientation="horizontal" android:padding="8dp">""" + \
    chip("q_note", "new_note") + chip("q_planner", "planner") + chip("q_import", "import_file") + """
</LinearLayout>"""

for k, v in F.items():
    p = os.path.join(R, k)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(v + "\n")
print("written", len(F))
