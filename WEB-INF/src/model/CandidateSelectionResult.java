package model;

import java.util.ArrayList;
import java.util.List;

/**
 * 候補選択結果
 * @originstopid 選択された出発停留所ID
 * @deststopid 選択された目的停留所ID
 * @shouldReturn 検索結果を返すかどうか
 */
public class CandidateSelectionResult {
    public enum Status { OK, NOT_FOUND, NEED_CHOICE }

    public Status status = Status.OK;
    public String message;

    public Integer originStopId;
    public Integer destStopId;

    public List<Stop> originCandidates = new ArrayList<>();
    public List<Stop> destCandidates   = new ArrayList<>();

    public Stop fixedOriginStop;
    public Stop fixedDestStop;
}
