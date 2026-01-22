package model;

public class RouteDetail {
    public String error;       // エラー表示用（nullなら正常）
    public String backUrl;     // 「戻る」リンク

    public String originName;
    public String originType;
    public String destName;
    public String destType;

    public String arrivalHHMM; // 到着時刻（表示用）
    public int totalMinutes;

    public ResultItem item;    // ルート詳細の本体（payloadを含む）

    public ReservationInfo reservation; // 自転車が含まれる時だけ non-null

    public static class ReservationInfo {
        public int operatorId;
        public String operatorName;
        public String operatorContact;
        public int startPortId;
        public int endPortId;
    }
}
