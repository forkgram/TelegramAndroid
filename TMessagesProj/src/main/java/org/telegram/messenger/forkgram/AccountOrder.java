package org.telegram.messenger.forkgram;

import android.text.TextUtils;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;

public final class AccountOrder {

    private static final String KEY_ORDER = "fg_accountsOrder";
    private static final String SEPARATOR = ",";

    private AccountOrder() {
    }

    public static void sort(ArrayList<Integer> accounts) {
        final ArrayList<Long> order = load();
        if (order.isEmpty()) {
            accounts.sort(AccountOrder::compareByLoginTime);
            return;
        }
        accounts.sort((account1, account2) -> {
            final int index1 = order.indexOf(UserConfig.getInstance(account1).getClientUserId());
            final int index2 = order.indexOf(UserConfig.getInstance(account2).getClientUserId());
            if (index1 < 0 || index2 < 0) {
                return index1 == index2 ? compareByLoginTime(account1, account2) : (index1 < 0 ? 1 : -1);
            }
            return Integer.compare(index1, index2);
        });
    }

    public static void save(List<Integer> reordered) {
        final ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        sort(accounts);
        int index = 0;
        for (int i = 0; i < accounts.size() && index < reordered.size(); i++) {
            if (reordered.contains(accounts.get(i))) {
                accounts.set(i, reordered.get(index++));
            }
        }
        final ArrayList<Long> ids = new ArrayList<>();
        for (int account : accounts) {
            ids.add(UserConfig.getInstance(account).getClientUserId());
        }
        MessagesController.getGlobalMainSettings().edit()
            .putString(KEY_ORDER, TextUtils.join(SEPARATOR, ids))
            .apply();
    }

    private static int compareByLoginTime(int account1, int account2) {
        return Long.compare(UserConfig.getInstance(account1).loginTime, UserConfig.getInstance(account2).loginTime);
    }

    private static ArrayList<Long> load() {
        final ArrayList<Long> ids = new ArrayList<>();
        final String value = MessagesController.getGlobalMainSettings().getString(KEY_ORDER, null);
        if (TextUtils.isEmpty(value)) {
            return ids;
        }
        for (String part : value.split(SEPARATOR)) {
            try {
                final long id = Long.parseLong(part);
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }
}
