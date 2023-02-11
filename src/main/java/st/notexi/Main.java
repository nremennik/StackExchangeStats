package st.notexi;

import com.google.gson.Gson;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import st.notexi.model.ErrorDescription;
import st.notexi.model.Items;
import st.notexi.model.User;
import st.notexi.service.StackExchangeUsers;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private final static String BASE_URL = "https://api.stackexchange.com/";
    private final static int THROTHLING_LIMIT_PER_SEC = 30;
    private final static int PAGE_SIZE = 100;
    private final static int PAGES_LIMIT = 500;
    private final static String MIN_REPUTATION = "223";
    private final static String ACCESS_KEY = null;

    // With total (slow)
    // private final static String FIELDS_FILTER = "!d0OIIVTgrb09xZY)*aPuDD0EMy4(rCDQ0FUn";

    // Without total (faster)
    private final static String FIELDS_FILTER = "!4LuLg.7-ZhsbquMjWVxBEREydvyw9AmtZAlsKib";
    private final static Set<String> REQUIRED_TAGS = new HashSet<>();
    private final static Set<String> REQUIRED_COUNTRIES = new HashSet<>();

    static {
        REQUIRED_TAGS.add("java");
        REQUIRED_TAGS.add(".net");
        REQUIRED_TAGS.add("docker");
        REQUIRED_TAGS.add("c#");
        //        REQUIRED_TAGS.add("apigee");

        REQUIRED_COUNTRIES.add("romania");
        REQUIRED_COUNTRIES.add("moldova");
    }

    public static void main(String[] args) throws IOException {
        long startTime;
        startTime = System.currentTimeMillis();

        Map<String, String> params = new HashMap<>();
        params.put("pagesize", Integer.toString(PAGE_SIZE));
        params.put("order", "desc");
        params.put("sort", "reputation");
        params.put("site", "stackoverflow");
        params.put("min", MIN_REPUTATION);
        params.put("filter", FIELDS_FILTER);
        if (ACCESS_KEY != null) params.put("key", ACCESS_KEY);

        Retrofit retrofit = new Retrofit.Builder().baseUrl(BASE_URL).addConverterFactory(GsonConverterFactory.create()).build();
        StackExchangeUsers stackExchangeUsers = retrofit.create(StackExchangeUsers.class);
        Call<Items> call = stackExchangeUsers.getUsers(params);

        long previousReqTime = 0;
        Items items;
        int pageNo = 1;
        do {
            params.put("page", Integer.toString(pageNo));
            Response<Items> response = call.execute(); // Synchronous request

            if (!response.isSuccessful()) {
                ErrorDescription errorDescription;
                if (response.errorBody() != null) {
                    errorDescription = new Gson().fromJson(response.errorBody().string(), ErrorDescription.class);
                    System.err.printf("Server error %d (%s): %s\nError while processing request #%d\n", errorDescription.getErrorId(), errorDescription.getErrorName(), errorDescription.getErrorMessage(), pageNo);
                }
                else {
                    System.err.printf("Server error.\nError while processing request #%d\n", pageNo);
                }
                break;
            }
            items = response.body();
            if (items == null || items.getItems() == null) {
                System.err.printf("No users found!\nError while processing request #%d\n", pageNo);
                break;
            }
            call = call.clone();

            // Do we need to filter by user_type equalsTo "registered"?
            List<User> users = items.getItems()
                    .stream()
                    .filter(item -> item.getLocation() != null
                            &&
                            REQUIRED_COUNTRIES.stream()
                                    .anyMatch(country ->
                                            item.getLocation().toLowerCase().contains(country)))
                    .filter(item -> item.getAnswerCount() > 0)
                    .filter(item -> {
                        try {
                            if (item.getCollectives().get(0).getCollective().getTags()
                                    .stream()
                                    .noneMatch(REQUIRED_TAGS::contains))
                                return (false);
                        } catch (NullPointerException npe) {
                            return (false);
                        }
                        return (true);
                    }).collect(Collectors.toList());

            //            if (users.size() == 0) System.err.println(executionTime + " " + System.currentTimeMillis());
            users.forEach(u -> {
                System.out.print(u.getDisplayName() + "|"
                        + u.getLocation() + "|"
                        + u.getAnswerCount() + "|"
                        + u.getQuestionCount() + "|");

                final boolean[] first = {true};
                try {
                    u.getCollectives().get(0).getCollective().getTags()
                            .forEach(tag -> {
                                if (!first[0]) System.out.print(",");
                                else first[0] = false;
                                System.out.print(tag);
                            });
                } catch (NullPointerException ignored) // No tags, print nothing
                {
                }
                if (!first[0]) System.out.print("|");

                System.out.println(u.getDisplayName() + "|"
                        + u.getLink() + "|"
                        + u.getProfileImage() + "|");
            });

            // Check throttling
            long executionTime = System.currentTimeMillis() - previousReqTime;
            if (executionTime < 1000 / THROTHLING_LIMIT_PER_SEC || (items.getBackoff() != null)) {
                long timeout;
                if (items.getBackoff() != null) timeout = items.getBackoff() * 1000L;
                else timeout = 1000 / THROTHLING_LIMIT_PER_SEC - executionTime;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException ignored) {
                    System.err.println("Interrupted!");
                }
            }
            previousReqTime = System.currentTimeMillis();

            System.err.println("Page " + pageNo + " processed.");
            pageNo++;
        } while (pageNo < PAGES_LIMIT);

        long endTime = System.currentTimeMillis();
        System.err.printf("Done in %d milliseconds (%f seconds)\n", endTime - startTime, (endTime - startTime) / 1000.0);
    }
}
