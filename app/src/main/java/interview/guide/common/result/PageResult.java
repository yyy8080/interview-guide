package interview.guide.common.result;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 分页结果
 *
 * @param page 页码（从 1 开始）
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

  public static <E, T> PageResult<T> of(Page<E> source, Function<E, T> mapper) {
    return new PageResult<>(source.getContent().stream().map(mapper).toList(),
        source.getTotalElements(), source.getNumber() + 1, source.getSize());
  }
}
