// FloatingCalculatorService.java
package com.example.simple_floating_calc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout.LayoutParams;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;

public class FloatingCalculatorService extends Service {
    private WindowManager windowManager;
    private View calculatorView;
    private TextView displayTextView;
    private ScrollView historyScrollView;
    private LinearLayout historyLayout;
    private StringBuilder currentInput = new StringBuilder();
    private boolean isResultShown = false;
    private NumberFormat numberFormat;
    private List<CalculationHistory> calculationHistory = new ArrayList<>();
    private String lastResult = "";

    private static final String PREFS_NAME = "CalculatorPrefs";
    private static final String KEY_POSITION_X = "position_x";
    private static final String KEY_POSITION_Y = "position_y";
    private static final String KEY_HISTORY = "calculation_history";
    private static final int MAX_HISTORY_SIZE = 100;

    // 履歴を保存するためのクラス
    private static class CalculationHistory {
        private final String expression;
        private final String result;

        public CalculationHistory(String expression, String result) {
            this.expression = expression;
            this.result = result;
        }

        public String getExpression() {
            return expression;
        }

        public String getResult() {
            return result;
        }

        @Override
        public String toString() {
            return expression + " = " + result;
        }
    }

    private final Map<Integer, String> buttonValues = new HashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // NumberFormat初期化 - カンマ区切りのための設定
        numberFormat = NumberFormat.getNumberInstance(Locale.getDefault());

        // Android 8.0以上の場合、フォアグラウンドサービスとして実行する必要がある
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "calculator_channel";
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "フローティング電卓",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("フローティング電卓")
                    .setSmallIcon(R.drawable.ic_calculator)
                    .build();

            startForeground(1, notification);
        }

        // 保存された計算履歴を読み込む
        loadHistory();

        // ウィンドウマネージャーの取得
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 電卓のレイアウトをインフレート
        calculatorView = LayoutInflater.from(this).inflate(R.layout.calculator_layout, null);

        // 表示部分の参照を取得
        displayTextView = calculatorView.findViewById(R.id.display);

        // 履歴部分の参照を取得
        historyScrollView = calculatorView.findViewById(R.id.historyScrollView);
        historyLayout = calculatorView.findViewById(R.id.historyLayout);

        // ボタンの値をマッピング
        initButtonValues();

        // ボタンのクリックリスナーを設定
        setupButtonListeners();

        // ドラッグ操作の実装
        setupDragListener();

        // 前回の位置を取得
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int posX = prefs.getInt(KEY_POSITION_X, 0);
        int posY = prefs.getInt(KEY_POSITION_Y, 100);

        // ウィンドウのレイアウトパラメータを設定
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = posX;
        params.y = posY;

        // ウィンドウマネージャーに電卓ビューを追加
        windowManager.addView(calculatorView, params);

        // 履歴表示を更新
        updateHistoryView();
    }

    private void initButtonValues() {
        buttonValues.put(R.id.button_0, "0");
        buttonValues.put(R.id.button_1, "1");
        buttonValues.put(R.id.button_2, "2");
        buttonValues.put(R.id.button_3, "3");
        buttonValues.put(R.id.button_4, "4");
        buttonValues.put(R.id.button_5, "5");
        buttonValues.put(R.id.button_6, "6");
        buttonValues.put(R.id.button_7, "7");
        buttonValues.put(R.id.button_8, "8");
        buttonValues.put(R.id.button_9, "9");
        buttonValues.put(R.id.button_plus, "+");
        buttonValues.put(R.id.button_minus, "-");
        buttonValues.put(R.id.button_multiply, "*");
        buttonValues.put(R.id.button_divide, "/");
        buttonValues.put(R.id.button_decimal, ".");
    }

    private void setupButtonListeners() {
        // 数字と演算子ボタンのリスナー設定
        for (Map.Entry<Integer, String> entry : buttonValues.entrySet()) {
            Button button = calculatorView.findViewById(entry.getKey());
            final String value = entry.getValue();
            button.setOnClickListener(v -> {
                // 演算子かどうか確認
                boolean isOperator = value.equals("+") || value.equals("-") ||
                        value.equals("*") || value.equals("/");

                // 計算結果表示直後に演算子が入力された場合、直前の結果を使用
                if (isResultShown && isOperator) {
                    // 現在の入力を直前の計算結果に置き換え
                    currentInput.setLength(0);
                    currentInput.append(lastResult);
                    isResultShown = false;
                } else if (isResultShown) {
                    // 演算子以外の入力の場合はクリア
                    currentInput.setLength(0);
                    isResultShown = false;
                }

                // 演算子の連続入力を防止
                if (isOperator && currentInput.length() > 0) {
                    // 最後の文字を取得
                    char lastChar = currentInput.charAt(currentInput.length() - 1);
                    // 最後の文字が演算子の場合は置き換える
                    if (lastChar == '+' || lastChar == '-' || lastChar == '*' || lastChar == '/') {
                        currentInput.deleteCharAt(currentInput.length() - 1);
                    }
                    // 式が空の場合に演算子'+', '*', '/'は無視（'-'は負の数として許可）
                    else if (currentInput.length() == 0 && (value.equals("+") || value.equals("*") || value.equals("/"))) {
                        return;
                    }
                }

                currentInput.append(value);
                updateDisplay();
            });
        }

        // クリアボタン
        Button clearButton = calculatorView.findViewById(R.id.button_clear);
        clearButton.setOnClickListener(v -> {
            currentInput.setLength(0);
            displayTextView.setText("0");
            isResultShown = false;
        });

        // バックスペースボタン
        Button backspaceButton = calculatorView.findViewById(R.id.button_backspace);
        backspaceButton.setOnClickListener(v -> {
            if (currentInput.length() > 0) {
                currentInput.deleteCharAt(currentInput.length() - 1);
                updateDisplay();
            }
            isResultShown = false;
        });

        // イコールボタン
        Button equalsButton = calculatorView.findViewById(R.id.button_equals);
        equalsButton.setOnClickListener(v -> {
            // イコールボタンが連続で押された場合は無視
            if (isResultShown) {
                return;
            }
            calculateAndDisplayResult();
        });

        // 閉じるボタン
        Button closeButton = calculatorView.findViewById(R.id.button_close);
        closeButton.setOnClickListener(v -> {
            // 現在の位置を保存してからサービスを終了
            saveCurrentPosition();
            saveHistory();
            stopSelf();
        });
    }

    private void calculateAndDisplayResult() {
        try {
            String expression = currentInput.toString();
            if (expression.isEmpty()) {
                return;
            }

            // exp4jを使用して数式を評価
            Expression e = new ExpressionBuilder(expression).build();
            double result = e.evaluate();

            // 結果を整数か小数で表示
            String resultStr;
            if (result == (long) result) {
                resultStr = String.valueOf((long) result);
            } else {
                resultStr = String.valueOf(result);
            }

            // 履歴に追加
            addToHistory(expression, resultStr);

            // 直前の計算結果を保存
            lastResult = resultStr;

            displayTextView.setText(formatNumber(resultStr));
            currentInput.setLength(0);
            currentInput.append(resultStr);
            isResultShown = true;

            // 計算履歴を保存
            saveHistory();
        } catch (Exception e) {
            displayTextView.setText("エラー");
            currentInput.setLength(0);
            isResultShown = true;
        }
    }

    private void addToHistory(String expression, String result) {
        CalculationHistory history = new CalculationHistory(expression, result);
        calculationHistory.add(history);

        // 最大履歴数を超えた場合、古いものから削除
        if (calculationHistory.size() > MAX_HISTORY_SIZE) {
            calculationHistory.remove(0);
        }

        // 履歴表示を更新
        updateHistoryView();
    }

    private void updateHistoryView() {
        // 履歴レイアウトをクリア
        historyLayout.removeAllViews();

        // 古いものから順に表示
        for (int i = 0; i < calculationHistory.size(); i++) {
            CalculationHistory history = calculationHistory.get(i);
            TextView historyItem = new TextView(this);
            historyItem.setText(history.toString());
            historyItem.setPadding(10, 5, 10, 5);
            historyItem.setTextSize(16);

            // 履歴アイテムのクリックリスナー
            historyItem.setOnClickListener(v -> {
                // 現在の入力に演算子が含まれているか確認
                String currentText = currentInput.toString();
                boolean hasOperator = currentText.contains("+") ||
                        currentText.contains("-") ||
                        currentText.contains("*") ||
                        currentText.contains("/");

                // 演算子の後であれば、履歴の結果を入力に追加
                if (hasOperator && !currentText.isEmpty()) {
                    char lastChar = currentText.charAt(currentText.length() - 1);
                    if (lastChar == '+' || lastChar == '-' || lastChar == '*' || lastChar == '/') {
                        currentInput.append(history.getResult());
                        updateDisplay();
                    }
                }
            });

            // レイアウトに履歴アイテムを追加
            historyLayout.addView(historyItem);

            // セパレータを追加（最後のアイテム以外）
            if (i < calculationHistory.size() - 1) {
                View separator = new View(this);
                separator.setBackgroundColor(0xFFCCCCCC);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT, 1);
                params.setMargins(0, 5, 0, 5);
                separator.setLayoutParams(params);
                historyLayout.addView(separator);
            }
        }

        // スクロールを一番下に移動
        historyScrollView.post(() -> historyScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String formatNumber(String number) {
        try {
            // 数値文字列をパースする
            if (number.contains(".")) {
                // 小数部分がある場合
                String[] parts = number.split("\\.");
                double integerPart = Double.parseDouble(parts[0]);
                return numberFormat.format(integerPart) + "." + parts[1];
            } else {
                // 整数の場合
                double value = Double.parseDouble(number);
                return numberFormat.format(value);
            }
        } catch (Exception e) {
            // パース中にエラーが発生した場合は元の数値を返す
            return number;
        }
    }

    private void updateDisplay() {
        if (currentInput.length() > 0) {
            String displayText = currentInput.toString();
            // 演算子を含む場合は、数値部分のみをフォーマット
            if (displayText.matches(".*[+\\-*/].*")) {
                // 演算子で分割
                String[] parts = displayText.split("(?<=[+\\-*/])|(?=[+\\-*/])");
                StringBuilder formattedExpression = new StringBuilder();
                for (String part : parts) {
                    if (part.matches("[+\\-*/]")) {
                        formattedExpression.append(part);
                    } else if (!part.isEmpty()) {
                        try {
                            formattedExpression.append(formatNumber(part));
                        } catch (Exception e) {
                            formattedExpression.append(part);
                        }
                    }
                }
                displayTextView.setText(formattedExpression.toString());
            } else {
                // 数値のみの場合
                displayTextView.setText(formatNumber(displayText));
            }
        } else {
            displayTextView.setText("0");
        }
    }

    private void setupDragListener() {
        final View dragHandle = calculatorView.findViewById(R.id.drag_handle);
        final int[] initialX = new int[1];
        final int[] initialY = new int[1];
        final float[] initialTouchX = new float[1];
        final float[] initialTouchY = new float[1];

        dragHandle.setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) calculatorView.getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX[0] = params.x;
                    initialY[0] = params.y;
                    initialTouchX[0] = event.getRawX();
                    initialTouchY[0] = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = initialX[0] + (int) (event.getRawX() - initialTouchX[0]);
                    params.y = initialY[0] + (int) (event.getRawY() - initialTouchY[0]);
                    windowManager.updateViewLayout(calculatorView, params);
                    return true;

                case MotionEvent.ACTION_UP:
                    // 移動が終わったら位置を保存
                    saveCurrentPosition();
                    return true;
            }
            return false;
        });
    }

    // 現在の位置を保存
    private void saveCurrentPosition() {
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) calculatorView.getLayoutParams();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt(KEY_POSITION_X, params.x);
        editor.putInt(KEY_POSITION_Y, params.y);
        editor.apply();
    }

    // 計算履歴を保存
    private void saveHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Gson gson = new Gson();
        String json = gson.toJson(calculationHistory);
        editor.putString(KEY_HISTORY, json);
        editor.apply();
    }

    // 計算履歴を読み込み
    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Gson gson = new Gson();
        String json = prefs.getString(KEY_HISTORY, null);
        if (json != null) {
            Type type = new TypeToken<ArrayList<CalculationHistory>>() {}.getType();
            calculationHistory = gson.fromJson(json, type);
            // 履歴が最大数を超えないようにする
            if (calculationHistory.size() > MAX_HISTORY_SIZE) {
                calculationHistory = calculationHistory.subList(
                        calculationHistory.size() - MAX_HISTORY_SIZE,
                        calculationHistory.size());
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // サービス終了時に現在の状態を保存
        saveCurrentPosition();
        saveHistory();
        if (calculatorView != null && windowManager != null) {
            windowManager.removeView(calculatorView);
        }
    }
}