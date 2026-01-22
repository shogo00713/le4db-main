package model;

/**
 * 経路詳細情報
 * @error エラー表示用（nullなら正常）
 * @backUrl 「戻る」リンク
 * @originName 出発地名
 * @originType 出発地タイプ
 * @destName 目的地名
 * @destType 目的地タイプ
 * @arrivalHHMM 到着時刻（表示用）
 * @totalMinutes 総所要時間（分）
 * @item ルート詳細の本体（payloadを含む）
 * @reservation 自転車が含まれる時だけ non-null
 */
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

    /**
     * 自転車予約情報
     * @operatorId 事業者ID
     * @operatorName 事業者名
     * @operatorContact 事業者連絡先
     * @startPortId 出発ポートID
     * @endPortId 到着ポートID
     */
    public static class ReservationInfo {
        public int operatorId;
        public String operatorName;
        public String operatorContact;
        public int startPortId;
        public int endPortId;
    }
}
