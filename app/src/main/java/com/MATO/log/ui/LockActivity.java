package com.MATO.log.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.util.SecurityHelper;

/**
 * 应用锁。
 *
 * MODE_SETUP：第一次使用时询问要不要设置口令，可以直接跳过。
 * MODE_VERIFY：进入应用前校验口令。
 */
public class LockActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_SETUP = 0;
    public static final int MODE_VERIFY = 1;

    private int mode;
    private boolean confirmed;
    private String firstPass = "";

    private TextView title;
    private TextView msg;
    private TextView error;
    private EditText input;
    private Button confirmBtn;
    private Button skipBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_VERIFY);

        title = findViewById(R.id.lock_title);
        msg = findViewById(R.id.lock_msg);
        error = findViewById(R.id.lock_error);
        input = findViewById(R.id.lock_input);
        confirmBtn = findViewById(R.id.lock_confirm);
        skipBtn = findViewById(R.id.lock_skip);

        if (mode == MODE_SETUP) {
            title.setText(R.string.lock_setup_title);
            msg.setText(R.string.lock_setup_msg);
            msg.setVisibility(View.VISIBLE);
            skipBtn.setVisibility(View.VISIBLE);
        } else {
            title.setText(R.string.lock_verify_title);
            msg.setVisibility(View.GONE);
            skipBtn.setVisibility(View.GONE);
        }

        confirmBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction();
            }
        });
        skipBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SecurityHelper.markAsked(LockActivity.this);
                setResult(RESULT_CANCELED);
                Toast.makeText(LockActivity.this, R.string.lock_skipped, Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                error.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void onAction() {
        String pass = input.getText() == null ? "" : input.getText().toString().trim();

        if (mode == MODE_VERIFY) {
            if (SecurityHelper.verify(this, pass)) {
                setResult(RESULT_OK);
                finish();
            } else {
                showError(getString(R.string.lock_wrong));
                input.setText("");
            }
            return;
        }

        // MODE_SETUP
        if (pass.length() < 4) {
            showError(getString(R.string.passcode_too_short));
            return;
        }
        if (!confirmed) {
            firstPass = pass;
            confirmed = true;
            input.setText("");
            title.setText(R.string.lock_confirm_title);
            msg.setVisibility(View.GONE);
            return;
        }
        if (!firstPass.equals(pass)) {
            confirmed = false;
            firstPass = "";
            input.setText("");
            title.setText(R.string.lock_setup_title);
            showError(getString(R.string.lock_mismatch));
            return;
        }
        SecurityHelper.setPasscode(this, pass);
        setResult(RESULT_OK);
        Toast.makeText(this, R.string.passcode_enabled, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showError(String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (mode == MODE_SETUP) {
            SecurityHelper.markAsked(this);
            setResult(RESULT_CANCELED);
            Toast.makeText(this, R.string.lock_skipped, Toast.LENGTH_SHORT).show();
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /** 打开口令校验界面 */
    public static Intent verifyIntent(android.app.Activity act) {
        Intent it = new Intent(act, LockActivity.class);
        it.putExtra(EXTRA_MODE, MODE_VERIFY);
        return it;
    }

    /** 打开首次设置界面 */
    public static Intent setupIntent(android.app.Activity act) {
        Intent it = new Intent(act, LockActivity.class);
        it.putExtra(EXTRA_MODE, MODE_SETUP);
        return it;
    }
}
