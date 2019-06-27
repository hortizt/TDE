package users;

import com.google.gson.JsonObject;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;

public interface UsersPort {

    @Nonnull
    List<JsonObject> users() throws IOException;

    JsonObject user(@Nonnull String login) throws IOException;
}
