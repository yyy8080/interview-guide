package interview.guide.modules.recruitment.model;

import java.util.List;

public record BatchReviewResult(List<Long> succeeded, List<Failure> failed) {

  public record Failure(Long id, String message) {
  }
}
