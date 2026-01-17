package model;

/**
 * 自転車乗換経路プラン
 * 出発地 → (徒歩) → ポート → (自転車) → 停留所 → (乗車) → 目的地
 * @walk0 出発地→ポート徒歩経路
 * @bike 自転車経路
 * @walk1 ポート→停留所徒歩経路
 * @leg2 乗車経路
 * @walk2 最後の徒歩経路
 * @totalMin 総所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class TransferBikeTransit {
    public final WalkPath walk0;
    public final BikePath bike;
    public final WalkPath walk1;
    public final DirectPath leg2;
    public final WalkPath walk2;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public TransferBikeTransit(WalkPath walk0, BikePath bike, WalkPath walk1, DirectPath leg2, WalkPath walk2, int totalMin, String startTime, String endTime) {
        this.walk0 = walk0;
        this.bike = bike;
        this.walk1 = walk1;
        this.leg2 = leg2;
        this.walk2 = walk2;
        this.totalMin = totalMin;
        this.startTime = startTime;
        this.endTime = endTime;
    }    
}