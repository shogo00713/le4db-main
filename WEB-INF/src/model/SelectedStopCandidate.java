package model;

/**
 * 選択された停留所候補
 * @stopId 停留所ID
 * @stopName 停留所名
 * @stopType 停留所タイプ
 */
public class SelectedStopCandidate {
    public final int stopId;
    public final String stopName;
    public final String stopType;

public SelectedStopCandidate(int stopId, String stopName, String stopType) {
        this.stopId = stopId;
        this.stopName = stopName;
        this.stopType = stopType;
    }
}