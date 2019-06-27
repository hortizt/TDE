package users;

import com.google.gson.annotations.SerializedName;

public class User {
    @SerializedName("login")
    public String login;
    @SerializedName("mainResourceId")
    public String resourceId;
}
