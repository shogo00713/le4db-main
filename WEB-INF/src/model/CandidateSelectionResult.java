package model;

/**
 * 候補選択結果
 * @originstopid 選択された出発停留所ID
 * @deststopid 選択された目的停留所ID
 * @shouldReturn 検索結果を返すかどうか
 */
public class CandidateSelectionResult {
    public Integer originstopid;
    public Integer deststopid;
    public boolean shouldReturn;
}
