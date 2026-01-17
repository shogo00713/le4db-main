package model;

/**
 * 自転車経路プラン
 * @operatorId 事業者ID
 * @operatorName 事業者名
 * @operatorContact 事業者連絡先
 * @fromPortId 出発ポートID
 * @fromPortName 出発ポート名
 * @toPortId 到着ポートID
 * @toPortName 到着ポート名
 * @distanceM 距離(m)
 * @rideMinutes 乗車時間(分)
 * @startTime 出発時刻 "HH:mm"(解錠後)
 * @endTime 到着時刻 "HH:mm"(到着)
 */
public class BikePath {
    public final int operatorId;
    public final String operatorName;
    public final String operatorContact;
    public final int fromPortId;
    public final String fromPortName;
    public final int toPortId;
    public final String toPortName;
    public final int distanceM;
    public final int rideMinutes;
    public final String startTime;
    public final String endTime;

    public BikePath(int operatorId, String operatorName, String operatorContact, int fromPortId, String fromPortName, int toPortId, String toPortName, int distanceM, int rideMinutes, String startTime, String endTime) {
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.operatorContact = operatorContact;
        this.fromPortId = fromPortId;
        this.fromPortName = fromPortName;
        this.toPortId = toPortId;
        this.toPortName = toPortName;
        this.distanceM = distanceM;
        this.rideMinutes = rideMinutes;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}