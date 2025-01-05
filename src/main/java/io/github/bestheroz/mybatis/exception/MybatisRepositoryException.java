package io.github.bestheroz.mybatis.exception;

public class MybatisRepositoryException extends RuntimeException {
  public MybatisRepositoryException(String message) {
    super(message);
  }

  public MybatisRepositoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
