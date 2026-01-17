package model;

/**
 * 徒歩経路情報
 * @fromName 出発地名
 * @toName 目的地名
 * @dist 距離(m)
 * @min 所要時間(分)
 */
public class WalkPath {
    public final String fromName;
    public final String toName;
    public final int dist;
    public final int min;

    public WalkPath(String fromName, String toName, int dist, int min) {
        this.fromName = fromName;
        this.toName = toName;
        this.dist = dist;
        this.min = min;
    }
}