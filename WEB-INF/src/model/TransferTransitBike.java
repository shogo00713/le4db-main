package model;

/**
 * 乗換1回＋レンタサイクル利用の経路プラン
 * 出発地 → (徒歩) → 停留所 → (乗車) → 停留所 → (徒歩) → ポート → (レンタサイクル) → ポート → (徒歩) → 目的地
 * @walk0 出発地→停留所 徒歩経路
 * @leg1 乗車経路
 * @walk1 停留所→ポート 徒歩経路
 * @bike レンタサイクル経路
 * @walk2 ポート→目的地 徒歩経路
 * @totalMin 総所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class TransferTransitBike {
    public final WalkPath walk0;
    public final DirectPath leg1;
    public final WalkPath walk1;
    public final BikePath bike;
    public final WalkPath walk2;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public TransferTransitBike(WalkPath walk0, DirectPath leg1, WalkPath walk1, BikePath bike, WalkPath walk2, int totalMin, String startTime, String endTime) {
        this.walk0 = walk0;
        this.leg1 = leg1;
        this.walk1 = walk1;
        this.bike = bike;
        this.walk2 = walk2;
        this.totalMin = totalMin;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}