package rest;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

public class TOAResult<T> {
    @Nullable
    private final T data;
    @Nullable
    private final IOException exception;


    public TOAResult(@Nullable T data, @Nullable IOException exception) {
        this.data = data;
        this.exception = exception;
    }

    public T get() throws IOException {
        if (exception != null)
            throw exception;
        return data;
    }

    public T orElse(T object) {
        if (exception != null)
            return object;
        return data;
    }

    public void ifSuccess(Consumer<T> handler) {
        if (exception == null)
            handler.accept(data);
    }

    public boolean isSuccess() {
        return exception == null;
    }

    public boolean isError() {
        return !isSuccess();
    }

    public T getOrElse(Function<IOException, T> handler) {
        if (data != null) return data;
        else return handler.apply(exception);
    }

    public static <T> TOAResult<T> of(T data) {
        return new TOAResult<>(data, null);
    }

    public static <T> TOAResult<T> ofError(IOException ex) {
        return new TOAResult<>(null, ex);
    }

    @Nullable
    public IOException getError() {
        return exception;
    }
}
