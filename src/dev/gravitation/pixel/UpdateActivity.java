package dev.gravitation.pixel;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;

public final class UpdateActivity extends Activity {
    private static final String API = "https://api.github.com/repos/gmdbx/pixel-gravity/releases?per_page=20";
    private static final String DOWNLOAD = "https://github.com/gmdbx/pixel-gravity/releases/download/";
    private static final int APK_LIMIT = 16 * 1024 * 1024;

    private TextView title;
    private TextView detail;
    private Button primary;
    private Button secondary;
    private String currentVersion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException failure) {
            finish();
            return;
        }
        createCard();
        check(false);
    }

    private void createCard() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int background = getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        int surface = getColor(dark ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_100);
        int foreground = getColor(dark ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
        int accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
        int onAccent = getColor(dark ? android.R.color.system_accent1_900 : android.R.color.system_accent1_0);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);

        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER);
        outer.setPadding(dp(20), dp(20), dp(20), dp(20));
        outer.setBackgroundColor(background);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(18));
        card.setBackground(shape(surface, 28));
        outer.addView(card, new LinearLayout.LayoutParams(
                Math.min(dp(400), getResources().getDisplayMetrics().widthPixels - dp(40)), -2));

        title = new TextView(this);
        title.setTextColor(foreground);
        title.setTextSize(22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        detail = new TextView(this);
        detail.setTextColor(foreground);
        detail.setTextSize(15);
        detail.setPadding(0, dp(8), 0, dp(16));
        card.addView(detail);
        LinearLayout actions = new LinearLayout(this);
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        primary = actionButton(accent, onAccent);
        secondary = actionButton(Color.TRANSPARENT, accent);
        actions.addView(primary, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        secondaryParams.setMarginStart(dp(8));
        actions.addView(secondary, secondaryParams);
        setContentView(outer);
    }

    private Button actionButton(int fill, int ink) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(ink);
        button.setStateListAnimator(null);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf((ink & 0xffffff) | 0x22000000),
                shape(fill, 24), shape(Color.WHITE, 24)));
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private void show(String heading, String description, String button, Runnable action,
            String other, Runnable otherAction) {
        title.setText(heading);
        detail.setText(description);
        primary.setText(button);
        primary.setEnabled(action != null);
        primary.setOnClickListener(action == null ? null : view -> action.run());
        secondary.setText(other);
        secondary.setOnClickListener(view -> otherAction.run());
    }

    private void check(boolean includeSkipped) {
        show("Pixel Gravity", "Проверяю обновления…", "Проверка", null, "Закрыть", this::finish);
        new Thread(() -> {
            try {
                String version = newestRelease();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (version == null) {
                        show("Установлена актуальная версия", "Версия " + currentVersion,
                                "Проверить", () -> check(true), "Закрыть", this::finish);
                    } else if (!includeSkipped && version.equals(
                            getPreferences(MODE_PRIVATE).getString("skipped_version", ""))) {
                        show("Обновление пропущено", "Версия " + version,
                                "Проверить", () -> check(true), "Закрыть", this::finish);
                    } else {
                        show("Доступно обновление", "Версия " + version,
                                "Обновить", () -> download(version), "Пропустить", () -> {
                                    getPreferences(MODE_PRIVATE).edit()
                                            .putString("skipped_version", version).apply();
                                    finish();
                                });
                    }
                });
            } catch (Exception failure) {
                showError("Попробуй проверить обновления ещё раз.", () -> check(includeSkipped));
            }
        }, "PixelGravityReleaseCheck").start();
    }

    private String newestRelease() throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (InputStream input = open(API)) {
            copy(input, response, 2 * 1024 * 1024);
        }
        JSONArray releases = new JSONArray(new String(response.toByteArray(), StandardCharsets.UTF_8));
        String latest = currentVersion;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            String tag = release.optString("tag_name");
            if (release.optBoolean("draft") || !tag.matches("v[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) continue;
            String version = tag.substring(1);
            if (compareVersions(version, latest) <= 0) continue;
            JSONArray assets = release.getJSONArray("assets");
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                if ((DOWNLOAD + tag + "/PixelGravity.apk").equals(asset.optString("browser_download_url"))) {
                    latest = version;
                    break;
                }
            }
        }
        return latest.equals(currentVersion) ? null : latest;
    }

    static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int a = i < left.length ? Integer.parseInt(left[i]) : 0;
            int b = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    private void download(String version) {
        show("Загрузка обновления", "Версия " + version, "Загрузка", null, "Закрыть", this::finish);
        new Thread(() -> {
            File temporary = null;
            try {
                temporary = File.createTempFile("update-", ".apk", getCacheDir());
                try (InputStream input = open(DOWNLOAD + "v" + version + "/PixelGravity.apk");
                        FileOutputStream output = new FileOutputStream(temporary)) {
                    copy(input, output, APK_LIMIT);
                }
                verifyApk(temporary, version);
                if (!temporary.renameTo(new File(getCacheDir(), "PixelGravity.apk"))) {
                    throw new IOException("Cannot save update");
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    show("Обновление готово", "Версия " + version,
                            "Установить", this::install, "Закрыть", this::finish);
                    install();
                });
            } catch (Exception failure) {
                showError("Не удалось загрузить или проверить APK.", () -> download(version));
            } finally {
                if (temporary != null) temporary.delete();
            }
        }, "PixelGravityApkDownload").start();
    }

    private void verifyApk(File apk, String version) throws Exception {
        PackageManager manager = getPackageManager();
        PackageInfo installed = manager.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
                PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !getPackageName().equals(candidate.packageName)
                || !version.equals(candidate.versionName)
                || candidate.getLongVersionCode() <= installed.getLongVersionCode()
                || candidate.signingInfo == null || installed.signingInfo == null
                || !signers(candidate).equals(signers(installed))) {
            throw new IOException("Unexpected APK package, version or signature");
        }
    }

    private static HashSet<Signature> signers(PackageInfo info) {
        return new HashSet<>(Arrays.asList(info.signingInfo.getApkContentsSigners()));
    }

    private void install() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            show("Разрешение на установку", "Разреши установку из Pixel Gravity в настройках Android.",
                    "Настройки", () -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()))), "Закрыть", this::finish);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(UpdateApkProvider.APK, UpdateApkProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException failure) {
            showError("Android не смог открыть установщик APK.", this::install);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (primary != null && "Настройки".contentEquals(primary.getText())
                && getPackageManager().canRequestPackageInstalls()) {
            show("Обновление готово", "Подтверди установку в Android.",
                    "Установить", this::install, "Закрыть", this::finish);
        }
    }

    private void showError(String message, Runnable retry) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                show("Обновление недоступно", message, "Повторить", retry, "Закрыть", this::finish);
            }
        });
    }

    private static InputStream open(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "PixelGravity-Updater");
        try {
            return new FilterInputStream(connection.getInputStream()) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            };
        } catch (IOException failure) {
            connection.disconnect();
            throw failure;
        }
    }

    private static void copy(InputStream input, OutputStream output, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int size; (size = input.read(buffer)) != -1; ) {
            total += size;
            if (total > limit) throw new IOException("Download exceeded size limit");
            output.write(buffer, 0, size);
        }
    }
}
