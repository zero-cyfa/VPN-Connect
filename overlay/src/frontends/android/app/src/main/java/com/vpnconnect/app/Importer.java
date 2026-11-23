package com.vpnconnect.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONException;
import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports Sophos Connect IPsec profiles (.scx). One file gives everything the tunnel needs, so
 * a connection is created (or updated, if the same gateway or name exists) from it directly.
 *
 * @author zero.cyfa
 */
final class Importer {
    private static final int MAX_BYTES = 512 * 1024;

    private Importer() {}

    /** Outcome of an import: a message for the user and whether everything worked. */
    static final class Result {
        final String message;
        final boolean ok;

        Result(String message, boolean ok) {
            this.message = message;
            this.ok = ok;
        }
    }

    static Result importAll(Context ctx, List<Uri> uris) {
        List<String> messages = new ArrayList<>();
        boolean allOk = true;
        for (Uri uri : uris) {
            Result r;
            try {
                r = importScx(ctx, read(ctx, uri), displayName(ctx, uri));
            } catch (IOException e) {
                r = new Result(ctx.getString(R.string.vc_file_unreadable), false);
            }
            messages.add(r.message);
            allOk &= r.ok;
        }
        StringBuilder out = new StringBuilder();
        for (String m : messages) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(m);
        }
        return new Result(out.toString(), allOk);
    }

    /** Imports a profile whose contents were shared as text rather than as a file. */
    static Result importText(Context ctx, String text) {
        String s = text.trim();
        return importScx(ctx, s.startsWith("﻿") ? s.substring(1) : s, null);
    }

    private static Result importScx(Context ctx, String text, String fileName) {
        ScxFile scx;
        try {
            scx = ScxFile.parse(text);
        } catch (ScxFile.ScxException e) {
            return new Result(ctx.getString(R.string.vc_scx_invalid), false);
        }

        ConnectionStore store = ConnectionStore.get(ctx);
        VpnEntry entry = store.findByGateway(scx.gateway);
        if (entry == null) {
            entry = store.findByName(scx.name);
        }

        VpnProfile profile = entry == null ? null : Profiles.find(ctx, entry.profileUuid);
        boolean create = profile == null;
        if (create) {
            profile = new VpnProfile();
        }
        try {
            profile.setName(scx.name);
            profile.setGateway(scx.gateway);
            profile.setVpnType(VpnType.IKEV1_XAUTH_PSK);
            profile.setIkeProposal(scx.ikeProposal);
            profile.setEspProposal(scx.espProposal);
            profile.setIncludedSubnets(TextUtilsJoin.join(" ", scx.networks));
            profile.setIkev1Options(scx.optionsJson());
        } catch (JSONException e) {
            return new Result(ctx.getString(R.string.vc_scx_invalid), false);
        }

        if (create) {
            profile = Profiles.insert(ctx, profile);
            if (profile == null) {
                return new Result(ctx.getString(R.string.vc_import_failed), false);
            }
        } else if (!Profiles.update(ctx, profile)) {
            return new Result(ctx.getString(R.string.vc_import_failed), false);
        }

        boolean existed = entry != null;
        if (entry == null) {
            entry = new VpnEntry(UUID.randomUUID().toString(), scx.name, scx.gateway);
            store.add(entry);
        }
        entry.name = scx.name;
        entry.gateway = scx.gateway;
        if (fileName != null) {
            entry.fileName = fileName;
        }
        entry.networks.clear();
        entry.networks.addAll(scx.networks);
        entry.profileUuid = profile.getUUID().toString();
        store.save();
        return new Result(ctx.getString(existed
                ? R.string.vc_updated_scx : R.string.vc_imported_scx, entry.name), true);
    }

    /** The file's name as the picker or file manager reports it, e.g. "office_vpn.scx". */
    private static String displayName(Context ctx, Uri uri) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                name = c.getString(0);
            }
        } catch (RuntimeException e) {
            name = null;
        }
        if (name == null || name.trim().isEmpty()) {
            name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) {
                name = name.substring(name.lastIndexOf('/') + 1);
            }
        }
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    private static String read(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("cannot open " + uri);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("file too large");
                }
                out.write(buf, 0, n);
            }
            String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return s.startsWith("\uFEFF") ? s.substring(1) : s;
        }
    }

    /** android.text.TextUtils.join without the Android dependency in unit tests. */
    private static final class TextUtilsJoin {
        static String join(String sep, List<String> items) {
            StringBuilder sb = new StringBuilder();
            for (String s : items) {
                if (sb.length() > 0) {
                    sb.append(sep);
                }
                sb.append(s);
            }
            return sb.toString();
        }
    }
}
