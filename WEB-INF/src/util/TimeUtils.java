package util;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    
    /**
     * 時刻（HH:mm形式）に指定分数を加算
     * @param time 基準時刻（例: "14:30"）
     * @param minutes 加算する分数
     * @return 計算後の時刻（HH:mm形式）
     */
    public static String addMinutes(String time, int minutes) {
        try {
            LocalTime localTime = LocalTime.parse(time, TIME_FORMATTER);
            LocalTime result = localTime.plusMinutes(minutes);
            return result.format(TIME_FORMATTER);
        } catch (Exception e) {
            return time; // パースエラー時は元の値を返す
        }
    }
    
    /**
     * 2つの時刻の差分を分単位で計算
     * 日跨ぎも対応
     * @param startTime 開始時刻（HH:mm）
     * @param endTime 終了時刻（HH:mm）
     * @return 差分（分）
     */
    public static int diffMinutes(String startTime, String endTime) {
        try {
            LocalTime start = LocalTime.parse(startTime, TIME_FORMATTER);
            LocalTime end = LocalTime.parse(endTime, TIME_FORMATTER);
            long m =java.time.Duration.between(start, end).toMinutes();
            if (m < 0) m += 24 * 60; // 日跨ぎ対応
            return (int) m;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 現在時刻をHH:mm形式で取得
     */
    public static String now() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    /**
     * HH:mm形式に統一
     * @param t  時間文字列
     * @return 必ずHH:mm形式の時間文字列
     */
    public static String hhmm(String t) {
        if (t == null)
            return "";
        return (t.length() >= 5) ? t.substring(0, 5) : t;
    }


}