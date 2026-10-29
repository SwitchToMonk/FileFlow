package com.fileflow.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;

/**
 * Serves a file to whatever app the user picks from the "Open with" sheet.
 * FileProvider lives in androidx, which this app does not depend on, so this is
 * a small hand-rolled equivalent: it hands out a read-only descriptor for a
 * path that was granted through the intent's URI permission.
 */
public class FileContentProvider extends ContentProvider {

    static final String AUTHORITY = "com.fileflow.app.files";

    @Override public boolean onCreate() { return true; }

    private File fileFor(Uri uri) {
        String p = uri.getPath();
        if (p == null) return null;
        File f = new File(p);
        return f.exists() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        File f = fileFor(uri);
        if (f == null) throw new java.io.FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFor(uri);
        if (f == null) return "*/*";
        String ext = FileEngine.extOf(f.getName());
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m == null ? "*/*" : m;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f == null ? "file" : f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f == null ? 0 : f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
