package com.vpnconnect.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.strongswan.android.R;
import org.strongswan.android.logic.VpnStateService.ErrorState;
import org.strongswan.android.logic.VpnStateService.State;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Main screen, laid out like the Sophos Connect desktop client: a dark bar with Connections and
 * Events tabs, a header showing the selected connection with a Connect button, a list of
 * connections, and an "Authenticate user" screen for the user name and password.
 *
 * @author zero.cyfa
 */
public class MainActivity extends Activity implements VpnController.Listener {
    private static final int REQ_IMPORT = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_VPN_PERMISSION = 1003;
    private static final int REQ_WHATSAPP_FOLDER = 1004;
    private static final int MENU_IMPORT = 1;
    private static final int MENU_LOG = 2;
    private static final int MENU_HELP = 3;
    private static final int MENU_ABOUT = 4;
    private static final int MENU_QUIT = 5;
    private static final int MENU_IMPORT_FILE = 6;
    private static final int MENU_IMPORT_WHATSAPP = 7;
    private static final int MENU_IMPORT_BROWSE_WHATSAPP = 8;

    private static final String[] WHATSAPP_PACKAGES = {"com.whatsapp", "com.whatsapp.w4b"};
    /** Where WhatsApp keeps received documents: under Android/media since Android 11, else in the storage root. */
    private static final String WHATSAPP_DOCS_NEW = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents";
    private static final String WHATSAPP_DOCS_OLD = "WhatsApp/Media/WhatsApp Documents";
    /** Makes the picker show internal storage; some phones hide it, and then ignore the start folder. */
    private static final String EXTRA_SHOW_ADVANCED = "android.provider.extra.SHOW_ADVANCED";
    private static final String EXTRA_SHOW_ADVANCED_OLD = "android.content.extra.SHOW_ADVANCED";
    private static final String STORAGE_PROVIDER = "com.android.externalstorage.documents";
    private static final String PREFS_IMPORT = "vc_import";
    /** Folder the user allowed once for "Browse WhatsApp Documents" (a document tree URI). */
    private static final String PREF_WHATSAPP_TREE = "whatsapp_docs_tree";
    private static final int SCAN_DEPTH = 3;
    private static final int SCAN_MAX = 200;

    private enum Ui { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

    // Mirrors of the service state; static so they survive the screen being recreated.
    private static State lastState = State.DISABLED;
    private static ErrorState lastError = ErrorState.NO_ERROR;
    private static long connectedAtMs;
    private static boolean startupLogged;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ConnectionStore store;
    private EventLog events;
    private VpnController controller;

    private TextView tabConnections;
    private TextView tabEvents;
    private ImageView notchConnections;
    private ImageView notchEvents;
    private View pageConnections;
    private View pageEvents;
    private TextView headerName;
    private TextView headerSub;
    private Button headerAction;
    private TextView panelTitle;
    private View listScroll;
    private LinearLayout listView;
    private TextView emptyView;
    private View authScroll;
    private EditText usernameField;
    private EditText passwordField;
    private Button signInButton;
    private LinearLayout eventsList;
    private TextView noEventsView;

    private boolean eventsPage;
    private boolean authMode;
    private boolean liveInfo;

    private VpnEntry pendingEntry;
    private String pendingPassword;

    private Object backCallback;
    private boolean backRegistered;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.vc_main);

        store = ConnectionStore.get(this);
        events = EventLog.get(this);
        controller = new VpnController(this);

        tabConnections = findViewById(R.id.vc_tab_connections);
        tabEvents = findViewById(R.id.vc_tab_events);
        notchConnections = findViewById(R.id.vc_notch_connections);
        notchEvents = findViewById(R.id.vc_notch_events);
        pageConnections = findViewById(R.id.vc_page_connections);
        pageEvents = findViewById(R.id.vc_page_events);
        headerName = findViewById(R.id.vc_header_name);
        headerSub = findViewById(R.id.vc_header_sub);
        headerAction = findViewById(R.id.vc_header_action);
        panelTitle = findViewById(R.id.vc_panel_title);
        listScroll = findViewById(R.id.vc_list_scroll);
        listView = findViewById(R.id.vc_list);
        emptyView = findViewById(R.id.vc_empty);
        authScroll = findViewById(R.id.vc_auth_scroll);
        usernameField = findViewById(R.id.vc_username);
        passwordField = findViewById(R.id.vc_password);
        signInButton = findViewById(R.id.vc_sign_in);
        eventsList = findViewById(R.id.vc_events_list);
        noEventsView = findViewById(R.id.vc_no_events);

        tabConnections.setOnClickListener(v -> showPage(false));
        tabEvents.setOnClickListener(v -> showPage(true));
        findViewById(R.id.vc_menu).setOnClickListener(this::showMenu);
        findViewById(R.id.vc_clear_events).setOnClickListener(v -> {
            events.clear();
            render();
        });

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSignInEnabled();
            }
        };
        usernameField.addTextChangedListener(watcher);
        passwordField.addTextChangedListener(watcher);
        passwordField.setOnEditorActionListener((view, actionId, event) -> {
            if (signInButton.isEnabled()) {
                signIn();
            }
            return true;
        });
        signInButton.setOnClickListener(v -> signIn());
        updateSignInEnabled();

        applyInsets(findViewById(R.id.vc_topbar), true);
        applyInsets(findViewById(R.id.vc_pages), false);

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBack();
                }
            };
        }

        requestNotificationPermission();
        logStartup();
        if (savedInstanceState == null) {
            handleIncomingIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.bind(this);
        ui.post(ticker);
        render();
    }

    @Override
    protected void onStop() {
        super.onStop();
        controller.unbind();
        ui.removeCallbacks(ticker);
    }

    private void logStartup() {
        if (startupLogged) {
            return;
        }
        startupLogged = true;
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        events.add(getString(R.string.vc_ev_started, version), true);
    }

    // ------------------------------------------------------------------ importing

    private void addImportItems(Menu menu) {
        menu.add(0, MENU_IMPORT_FILE, 0, R.string.vc_import_choose_file);
        menu.add(0, MENU_IMPORT_WHATSAPP, 1, R.string.vc_import_from_whatsapp);
        menu.add(0, MENU_IMPORT_BROWSE_WHATSAPP, 2, R.string.vc_import_browse_whatsapp);
    }

    /** @return true if the id was one of the import choices. */
    private boolean onImportItem(int id) {
        switch (id) {
            case MENU_IMPORT_FILE:
                openPicker();
                return true;
            case MENU_IMPORT_WHATSAPP:
                showWhatsAppGuide();
                return true;
            case MENU_IMPORT_BROWSE_WHATSAPP:
                browseWhatsAppDocuments();
                return true;
            default:
                return false;
        }
    }

    /** The header's Import button offers the same choices as the ⋮ menu's "Import connection". */
    private void showImportMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        addImportItems(menu.getMenu());
        menu.setOnMenuItemClickListener(item -> onImportItem(item.getItemId()));
        menu.show();
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.vc_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    // "Browse WhatsApp Documents": the user allows WhatsApp's documents folder once, then the app
    // lists the .scx files in it itself, so nobody has to find the folder in the file picker.

    private void browseWhatsAppDocuments() {
        Uri tree = savedWhatsAppFolder();
        if (tree != null) {
            scanForScx(tree);
        } else {
            askForWhatsAppFolder();
        }
    }

    /** @return the allowed folder, or null if none was allowed yet or the permission was lost. */
    private Uri savedWhatsAppFolder() {
        String saved = getSharedPreferences(PREFS_IMPORT, MODE_PRIVATE).getString(PREF_WHATSAPP_TREE, null);
        if (saved == null) {
            return null;
        }
        Uri uri = Uri.parse(saved);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.isReadPermission() && uri.equals(p.getUri())) {
                return uri;
            }
        }
        return null;
    }

    private void forgetWhatsAppFolder() {
        getSharedPreferences(PREFS_IMPORT, MODE_PRIVATE).edit().remove(PREF_WHATSAPP_TREE).apply();
    }

    private void askForWhatsAppFolder() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vc_wa_folder_title)
                .setMessage(R.string.vc_wa_folder_text)
                .setPositiveButton(R.string.vc_continue, (d, w) -> openWhatsAppFolderPicker())
                .setNegativeButton(R.string.vc_cancel, null)
                .show();
    }

    private void openWhatsAppFolderPicker() {
        String path = Build.VERSION.SDK_INT >= 30 ? WHATSAPP_DOCS_NEW : WHATSAPP_DOCS_OLD;
        Intent intent = null;
        if (Build.VERSION.SDK_INT >= 29) {
            intent = treeIntentFromStorageVolume(path);
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT >= 26) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(STORAGE_PROVIDER, "primary:" + path));
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(EXTRA_SHOW_ADVANCED, true);
        intent.putExtra(EXTRA_SHOW_ADVANCED_OLD, true);
        try {
            startActivityForResult(intent, REQ_WHATSAPP_FOLDER);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.vc_no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Android 10+: start from the folder picker intent the storage volume itself hands out (it
     * names the right storage root, which some phones require), and point its start folder at
     * the WhatsApp folder on that root.
     */
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private Intent treeIntentFromStorageVolume(String path) {
        try {
            StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
            if (sm == null) {
                return null;
            }
            Intent intent = sm.getPrimaryStorageVolume().createOpenDocumentTreeIntent();
            Parcelable root = intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
            if (root instanceof Uri) {
                // .../root/primary  ->  .../document/primary%3AAndroid%2Fmedia%2F...
                String start = root.toString().replace("/root/", "/document/") + "%3A" + Uri.encode(path);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(start));
            }
            return intent;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void onWhatsAppFolderChosen(Uri tree) {
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS_IMPORT, MODE_PRIVATE).edit()
                    .putString(PREF_WHATSAPP_TREE, tree.toString()).apply();
        } catch (SecurityException e) {
            // still readable for now; we just ask again next time
        }
        scanForScx(tree);
    }

    /** An .scx file found in the allowed folder. */
    private static final class FoundScx {
        final String name;
        final Uri uri;
        final long modified;

        FoundScx(String name, Uri uri, long modified) {
            this.name = name;
            this.uri = uri;
            this.modified = modified;
        }
    }

    private void scanForScx(final Uri tree) {
        Toast.makeText(this, R.string.vc_wa_scanning, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<FoundScx> found = new ArrayList<>();
            boolean readable = true;
            try {
                collectScx(tree, DocumentsContract.getTreeDocumentId(tree), 0, found);
            } catch (RuntimeException e) {
                readable = false; // permission gone or folder deleted
            }
            final boolean ok = readable || !found.isEmpty();
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    showScxChoices(found, ok);
                }
            });
        }, "vc-scan").start();
    }

    private void collectScx(Uri tree, String parentId, int depth, List<FoundScx> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        List<String> subfolders = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) {
                throw new IllegalStateException("cannot list " + children);
            }
            while (c.moveToNext() && out.size() < SCAN_MAX) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (id == null || name == null) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!name.startsWith(".")) {
                        subfolders.add(id);
                    }
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".scx")) {
                    out.add(new FoundScx(name, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            c.isNull(3) ? 0 : c.getLong(3)));
                }
            }
        }
        if (depth < SCAN_DEPTH) {
            for (String id : subfolders) {
                if (out.size() >= SCAN_MAX) {
                    break;
                }
                try {
                    collectScx(tree, id, depth + 1, out);
                } catch (RuntimeException ignored) {
                    // skip a subfolder we cannot read
                }
            }
        }
    }

    private void showScxChoices(final List<FoundScx> found, boolean readable) {
        if (!readable) {
            forgetWhatsAppFolder();
            askForWhatsAppFolder();
            return;
        }
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.vc_wa_none_title)
                    .setMessage(R.string.vc_wa_none_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.vc_wa_other_folder, (d, w) -> openWhatsAppFolderPicker())
                    .show();
            return;
        }
        Collections.sort(found, (a, b) -> Long.compare(b.modified, a.modified));
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault());
        String[] labels = new String[found.size()];
        for (int i = 0; i < labels.length; i++) {
            FoundScx f = found.get(i);
            labels[i] = f.modified > 0 ? f.name + "\n" + format.format(new Date(f.modified)) : f.name;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.vc_wa_pick_title)
                .setItems(labels, (d, which) -> runImport(Collections.singletonList(found.get(which).uri)))
                .setNegativeButton(R.string.vc_cancel, null)
                .setNeutralButton(R.string.vc_wa_other_folder, (d, w) -> openWhatsAppFolderPicker())
                .show();
    }

    private void showWhatsAppGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vc_whatsapp_title)
                .setMessage(R.string.vc_whatsapp_steps)
                .setPositiveButton(R.string.vc_open_whatsapp, (d, w) -> openWhatsApp())
                .setNegativeButton(R.string.vc_cancel, null)
                .show();
    }

    /**
     * Uses the app's own launch intent (an explicit component). A MAIN/LAUNCHER intent with only a
     * package set is still implicit, and startActivity() then also requires CATEGORY_DEFAULT,
     * which launcher screens don't declare, so it always failed with "not installed".
     */
    private void openWhatsApp() {
        PackageManager pm = getPackageManager();
        for (String pkg : WHATSAPP_PACKAGES) { // WhatsApp, then WhatsApp Business
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                continue;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next one
            }
        }
        Toast.makeText(this, R.string.vc_whatsapp_missing, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT) {
            if (resultCode != RESULT_OK || data == null) {
                return;
            }
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            runImport(uris);
        } else if (requestCode == REQ_WHATSAPP_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                onWhatsAppFolderChosen(data.getData());
            }
        } else if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startPending();
            } else {
                clearPending();
                events.add(getString(R.string.vc_ev_vpn_permission), false);
                render();
            }
        }
    }

    /** Files opened with ("Open with") or shared to the app, e.g. from WhatsApp or a file manager. */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        String text = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            if (intent.getData() != null) {
                uris.add(intent.getData());
            }
        } else if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            uris.addAll(sharedUris(intent));
            if (uris.isEmpty()) {
                text = intent.getStringExtra(Intent.EXTRA_TEXT);
            }
        } else {
            return;
        }
        // Handled once: don't import again when the screen is recreated.
        intent.setAction(null);
        intent.setData(null);

        if (!uris.isEmpty()) {
            runImport(uris);
        } else if (text != null && !text.trim().isEmpty()) {
            showImportResult(Importer.importText(this, text));
        } else {
            showImportResult(new Importer.Result(getString(R.string.vc_file_unreadable), false));
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> sharedUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) {
                    uris.add(u);
                }
            }
        }
        if (!uris.isEmpty()) {
            return uris;
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable p : list) {
                    if (p instanceof Uri && !uris.contains(p)) {
                        uris.add((Uri) p);
                    }
                }
            }
        } else {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) {
                uris.add((Uri) p);
            }
        }
        return uris;
    }

    private void runImport(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        showImportResult(Importer.importAll(this, uris));
    }

    private void showImportResult(Importer.Result result) {
        events.add(result.message.replace("\n\n", " "), result.ok);
        authMode = false;
        render();
        new AlertDialog.Builder(this)
                .setMessage(result.message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------ VPN state

    @Override
    public void onVpnChanged() {
        State state = controller.state();
        ErrorState error = controller.error();
        VpnEntry entry = store.findByProfile(controller.profileUuid());
        String name = entry != null ? entry.name : getString(R.string.app_name);

        if (state == State.CONNECTED && lastState != State.CONNECTED) {
            connectedAtMs = SystemClock.elapsedRealtime();
            events.add(getString(R.string.vc_ev_connected, name), true);
            if (entry != null) {
                entry.lastConnectedMs = System.currentTimeMillis();
                store.save();
            }
        } else if (error != ErrorState.NO_ERROR && error != lastError) {
            events.add(getString(R.string.vc_ev_error, name, getString(errorText(error))), false);
        } else if (state == State.DISABLED && lastState == State.CONNECTED) {
            events.add(getString(R.string.vc_ev_disconnected, name), true);
        }
        if (state != State.CONNECTED) {
            connectedAtMs = 0;
        } else if (connectedAtMs == 0) {
            connectedAtMs = SystemClock.elapsedRealtime();
        }
        lastState = state;
        lastError = error;
        render();
    }

    private static int errorText(ErrorState error) {
        switch (error) {
            case AUTH_FAILED:
                return R.string.vc_err_auth;
            case PEER_AUTH_FAILED:
                return R.string.vc_err_peer_auth;
            case LOOKUP_FAILED:
                return R.string.vc_err_lookup;
            case UNREACHABLE:
                return R.string.vc_err_unreachable;
            default:
                return R.string.vc_err_generic;
        }
    }

    private Ui uiStateFor(VpnEntry e) {
        if (e.profileUuid == null || !e.profileUuid.equals(controller.profileUuid())) {
            return Ui.IDLE;
        }
        if (controller.error() != ErrorState.NO_ERROR) {
            return Ui.ERROR;
        }
        switch (controller.state()) {
            case CONNECTED:
                return Ui.CONNECTED;
            case CONNECTING:
                return Ui.CONNECTING;
            case DISCONNECTING:
                return Ui.DISCONNECTING;
            default:
                return Ui.IDLE;
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        renderTabs();
        if (eventsPage) {
            renderEvents();
        } else {
            renderHeader();
            renderPanel();
        }
        updateBackCallback();
    }

    private void renderTabs() {
        tabConnections.setTextColor(eventsPage ? getColor(R.color.vc_tab_inactive) : Color.WHITE);
        tabEvents.setTextColor(eventsPage ? Color.WHITE : getColor(R.color.vc_tab_inactive));
        tabConnections.setTypeface(null, eventsPage ? Typeface.NORMAL : Typeface.BOLD);
        tabEvents.setTypeface(null, eventsPage ? Typeface.BOLD : Typeface.NORMAL);
        notchConnections.setVisibility(eventsPage ? View.GONE : View.VISIBLE);
        notchEvents.setVisibility(eventsPage ? View.VISIBLE : View.GONE);
        notchConnections.setColorFilter(getColor(R.color.vc_card), PorterDuff.Mode.SRC_IN);
        notchEvents.setColorFilter(getColor(R.color.vc_gray), PorterDuff.Mode.SRC_IN);
        pageConnections.setVisibility(eventsPage ? View.GONE : View.VISIBLE);
        pageEvents.setVisibility(eventsPage ? View.VISIBLE : View.GONE);
    }

    private void renderHeader() {
        liveInfo = false;
        VpnEntry e = store.selected();
        if (e == null) {
            headerName.setText(R.string.app_name);
            setSub(getString(R.string.vc_no_connections), R.color.vc_sub_gray);
            setAction(R.string.vc_import_short, this::showImportMenu);
            return;
        }
        // Title line: app name until something is imported, then the imported file's name.
        headerName.setText(e.fileName != null ? e.fileName : e.name);
        Ui state = uiStateFor(e);
        if (authMode) {
            setSub(getString(R.string.vc_auth_prompt), R.color.vc_sub_gray);
            setAction(R.string.vc_cancel, v -> cancelAuth());
        } else if (state == Ui.CONNECTED) {
            liveInfo = true;
            updateInfo();
            setAction(R.string.vc_disconnect, v -> controller.disconnect());
        } else if (state == Ui.CONNECTING) {
            setSub(getString(R.string.vc_status_connecting), R.color.vc_amber);
            setAction(R.string.vc_cancel, v -> controller.disconnect());
        } else if (state == Ui.DISCONNECTING) {
            setSub(getString(R.string.vc_status_disconnecting), R.color.vc_amber);
            setAction(R.string.vc_cancel, v -> controller.disconnect());
        } else if (state == Ui.ERROR) {
            setSub(getString(errorText(controller.error())), R.color.vc_red);
            setAction(R.string.vc_connect, v -> connectSelected());
        } else {
            setSub(lastConnectedText(e), R.color.vc_sub_gray);
            setAction(R.string.vc_connect, v -> connectSelected());
        }
    }

    private void setSub(String text, int colorRes) {
        headerSub.setText(text);
        headerSub.setTextColor(getColor(colorRes));
    }

    private void setAction(int labelRes, View.OnClickListener listener) {
        headerAction.setText(labelRes);
        headerAction.setOnClickListener(listener);
    }

    private void updateInfo() {
        if (!liveInfo) {
            return;
        }
        long seconds = connectedAtMs == 0 ? 0 : (SystemClock.elapsedRealtime() - connectedAtMs) / 1000;
        setSub(getString(R.string.vc_connected_for, DateUtils.formatElapsedTime(seconds)), R.color.vc_green);
    }

    private String lastConnectedText(VpnEntry e) {
        if (e.lastConnectedMs <= 0) {
            return getString(R.string.vc_never_connected);
        }
        SimpleDateFormat format = new SimpleDateFormat("EEEE, MMM d, yyyy '@' h:mm:ss a", Locale.getDefault());
        return getString(R.string.vc_last_connected, format.format(new Date(e.lastConnectedMs)));
    }

    private void renderPanel() {
        if (authMode) {
            panelTitle.setText(R.string.vc_auth_title);
            listScroll.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            authScroll.setVisibility(View.VISIBLE);
            return;
        }
        panelTitle.setText(R.string.vc_tab_connections);
        authScroll.setVisibility(View.GONE);

        List<VpnEntry> entries = store.all();
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        listScroll.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);

        listView.removeAllViews();
        VpnEntry selected = store.selected();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (final VpnEntry e : entries) {
            View row = inflater.inflate(R.layout.vc_item_connection, listView, false);
            TextView name = row.findViewById(R.id.vc_row_name);
            TextView sub = row.findViewById(R.id.vc_row_sub);
            View gear = row.findViewById(R.id.vc_row_gear);

            boolean isSelected = selected != null && selected.id.equals(e.id);
            row.setBackgroundResource(isSelected ? R.drawable.vc_row_selected : R.drawable.vc_row_normal);
            gear.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            name.setText(e.name);

            Ui state = uiStateFor(e);
            if (state == Ui.CONNECTED) {
                sub.setText(R.string.vc_status_connected);
                sub.setTextColor(getColor(R.color.vc_green));
            } else if (state == Ui.CONNECTING || state == Ui.DISCONNECTING) {
                sub.setText(state == Ui.CONNECTING ? R.string.vc_status_connecting : R.string.vc_status_disconnecting);
                sub.setTextColor(getColor(R.color.vc_amber));
            } else if (state == Ui.ERROR) {
                sub.setText(errorText(controller.error()));
                sub.setTextColor(getColor(R.color.vc_red));
            } else {
                sub.setText(lastConnectedText(e));
                sub.setTextColor(getColor(R.color.vc_sub_green));
            }

            row.setOnClickListener(v -> {
                store.select(e);
                authMode = false;
                render();
            });
            gear.setOnClickListener(v -> showDetails(e));
            listView.addView(row);
        }
    }

    private void renderEvents() {
        eventsList.removeAllViews();
        List<EventLog.Event> all = events.newestFirst();
        noEventsView.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-M-d h:mm:ss a", Locale.getDefault());
        LayoutInflater inflater = LayoutInflater.from(this);
        for (EventLog.Event ev : all) {
            View row = inflater.inflate(R.layout.vc_item_event, eventsList, false);
            ((ImageView) row.findViewById(R.id.vc_ev_icon))
                    .setImageResource(ev.ok ? R.drawable.vc_ic_check : R.drawable.vc_ic_error);
            ((TextView) row.findViewById(R.id.vc_ev_time)).setText(format.format(new Date(ev.time)));
            ((TextView) row.findViewById(R.id.vc_ev_text)).setText(ev.text);
            eventsList.addView(row);
        }
    }

    // ------------------------------------------------------------------ connecting

    private void connectSelected() {
        VpnEntry e = store.selected();
        if (e == null || e.profileUuid == null) {
            return;
        }
        usernameField.setText(Profiles.username(this, e.profileUuid));
        passwordField.setText("");
        authMode = true;
        render();
        final EditText target = usernameField.getText().length() == 0 ? usernameField : passwordField;
        target.requestFocus();
        target.post(() -> showKeyboard(target));
    }

    private void updateSignInEnabled() {
        boolean ok = usernameField.getText().toString().trim().length() > 0
                && passwordField.getText().length() > 0;
        signInButton.setEnabled(ok);
    }

    private void cancelAuth() {
        authMode = false;
        passwordField.setText("");
        hideKeyboard();
        render();
    }

    private void signIn() {
        VpnEntry e = store.selected();
        if (e == null || e.profileUuid == null) {
            return;
        }
        String user = usernameField.getText().toString().trim();
        String pass = passwordField.getText().toString();
        if (user.isEmpty() || pass.isEmpty()) {
            return;
        }
        hideKeyboard();
        passwordField.setText("");
        authMode = false;

        // The service reads the user name from the saved profile; the password travels in memory only.
        if (!Profiles.saveUsername(this, e.profileUuid, user)) {
            Toast.makeText(this, R.string.vc_import_failed, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        pendingEntry = e;
        pendingPassword = pass;

        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQ_VPN_PERMISSION);
            } catch (ActivityNotFoundException ex) {
                clearPending();
                Toast.makeText(this, R.string.vc_ev_vpn_permission, Toast.LENGTH_LONG).show();
            }
        } else {
            startPending();
        }
        render();
    }

    private void startPending() {
        if (pendingEntry != null && pendingEntry.profileUuid != null) {
            if (!controller.connect(pendingEntry.profileUuid, pendingPassword)) {
                Toast.makeText(this, R.string.vc_err_generic, Toast.LENGTH_LONG).show();
            }
        }
        clearPending();
    }

    private void clearPending() {
        pendingEntry = null;
        pendingPassword = null;
    }

    // ------------------------------------------------------------------ dialogs and menu

    private void showDetails(final VpnEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.vc_gateway, e.gateway)).append('\n');
        sb.append(getString(R.string.vc_networks, e.networks.isEmpty()
                ? getString(R.string.vc_details_networks_none) : TextUtils.join(", ", e.networks)));
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.vc_close, null)
                .setNegativeButton(R.string.vc_remove, (d, w) -> confirmRemove(e))
                .show();
    }

    private void confirmRemove(final VpnEntry e) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vc_remove_title)
                .setMessage(getString(R.string.vc_remove_message, e.name))
                .setPositiveButton(R.string.vc_remove, (d, w) -> removeEntry(e))
                .setNegativeButton(R.string.vc_cancel, null)
                .show();
    }

    private void removeEntry(VpnEntry e) {
        if (e.profileUuid != null) {
            if (uiStateFor(e) != Ui.IDLE) {
                controller.disconnect();
            }
            Profiles.delete(this, e.profileUuid);
        }
        store.remove(e);
        render();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        addImportItems(menu.getMenu().addSubMenu(0, MENU_IMPORT, 0, R.string.vc_menu_import));
        menu.getMenu().add(0, MENU_LOG, 1, R.string.vc_menu_log);
        menu.getMenu().add(0, MENU_HELP, 2, R.string.vc_menu_help);
        menu.getMenu().add(0, MENU_ABOUT, 3, R.string.vc_menu_about);
        menu.getMenu().add(0, MENU_QUIT, 4, R.string.vc_menu_quit);
        menu.setOnMenuItemClickListener(item -> {
            if (onImportItem(item.getItemId())) {
                return true;
            }
            switch (item.getItemId()) {
                case MENU_IMPORT:
                    return false; // let the submenu open
                case MENU_LOG:
                    startActivity(new Intent(this, LogActivity.class));
                    break;
                case MENU_HELP:
                    showText(R.string.vc_help_title, R.string.vc_help_body);
                    break;
                case MENU_ABOUT:
                    showText(R.string.vc_about_title, R.string.vc_about_body);
                    break;
                case MENU_QUIT:
                    finishAndRemoveTask();
                    break;
                default:
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void showText(int titleRes, int bodyRes) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------ navigation and platform bits

    private void showPage(boolean showEvents) {
        eventsPage = showEvents;
        if (showEvents) {
            authMode = false;
            hideKeyboard();
        }
        render();
    }

    /** @return true if the back press was used to step back inside the app. */
    private boolean handleBack() {
        if (authMode) {
            cancelAuth();
            return true;
        }
        if (eventsPage) {
            showPage(false);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (!handleBack()) {
            super.onBackPressed();
        }
    }

    /** Android 13+ delivers back through a callback; only intercept it while we have somewhere to go back to. */
    @SuppressLint("NewApi")
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || backCallback == null) {
            return;
        }
        boolean needed = authMode || eventsPage;
        if (needed && !backRegistered) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
            backRegistered = true;
        } else if (!needed && backRegistered) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backRegistered = false;
        }
    }

    private void showKeyboard(View field) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(field, 0);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    /** Android 15+ draws apps edge to edge; keep the bars clear of the system bars. */
    @SuppressWarnings("deprecation")
    private void applyInsets(final View bar, final boolean top) {
        final int basePad = top ? bar.getPaddingTop() : bar.getPaddingBottom();
        bar.setOnApplyWindowInsetsListener((view, insets) -> {
            if (top) {
                view.setPadding(view.getPaddingLeft(), basePad + insets.getSystemWindowInsetTop(),
                        view.getPaddingRight(), view.getPaddingBottom());
            } else {
                view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), basePad + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }
}
