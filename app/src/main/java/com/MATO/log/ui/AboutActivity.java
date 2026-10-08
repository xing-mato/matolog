package com.MATO.log.ui;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.MATO.log.R;

/** 关于：版本、包名、一句话说明 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        TextView version = findViewById(R.id.about_version);
        TextView pkg = findViewById(R.id.about_pkg);

        String versionName = "1.0.0";
        long versionCode = 100L;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) {
                versionName = info.versionName;
            }
            versionCode = PackageCompat.longVersionCode(info);
        } catch (Exception ignored) {
        }

        version.setText(getString(R.string.about_version, versionName, versionCode));
        pkg.setText(getString(R.string.about_pkg, getPackageName()));
    }

    /** versionCodeLong 是 API 28 才有的字段，低版本回落到 versionCode */
    private static final class PackageCompat {
        static long longVersionCode(PackageInfo info) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        }
    }
}
