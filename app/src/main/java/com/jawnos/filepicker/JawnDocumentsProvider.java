package com.jawnos.filepicker;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.jawnos.R;
import com.jawnos.shared.jawnos.JawnConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Exposes the app's home directory to the Storage Access Framework.
 *
 * The document id is the file's absolute path: it is stable across restarts and
 * the system stores it, so it must not change. The root is the same home the
 * tree uses, and a search never follows a symlink out of it.
 */
public class JawnDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";
    private static final int MAX_SEARCH_RESULTS = 50;
    private static final String ID_PREFIX = "home:";
    private static final File HOME = JawnConstants.TERMUX_HOME_DIR;

    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
        Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        MatrixCursor.RowBuilder row = cursor.newRow();
        String rootId = idFor(HOME);
        row.add(Root.COLUMN_ROOT_ID, rootId);
        row.add(Root.COLUMN_DOCUMENT_ID, rootId);
        row.add(Root.COLUMN_SUMMARY, null);
        row.add(Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, getContext().getString(R.string.application_name));
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, HOME.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        addDocumentRow(cursor, fileFor(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        File[] children = fileFor(parentDocumentId).listFiles();
        if (children != null) {
            for (File child : children) addDocumentRow(cursor, child);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
                                                    CancellationSignal signal) throws FileNotFoundException {
        File file = fileFor(documentId);
        ParcelFileDescriptor descriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(descriptor, 0, file.length());
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        File created = new File(parent, displayName);
        int suffix = 2;
        while (created.exists()) {
            created = new File(parent, displayName + " (" + suffix++ + ")");
        }
        boolean ok;
        try {
            ok = Document.MIME_TYPE_DIR.equals(mimeType) ? created.mkdir() : created.createNewFile();
        } catch (IOException e) {
            ok = false;
        }
        if (!ok) throw new FileNotFoundException("Could not create " + created.getPath());
        return idFor(created);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (!file.delete()) throw new FileNotFoundException("Could not delete " + documentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeFor(fileFor(documentId));
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        String needle = query == null ? "" : query.toLowerCase();
        String home;
        try {
            home = HOME.getCanonicalPath();
        } catch (IOException e) {
            home = HOME.getAbsolutePath();
        }

        Deque<File> pending = new ArrayDeque<>();
        pending.add(fileFor(rootId));
        while (!pending.isEmpty() && cursor.getCount() < MAX_SEARCH_RESULTS) {
            File file = pending.removeFirst();
            String canonical;
            try {
                canonical = file.getCanonicalPath();
            } catch (IOException e) {
                continue;
            }
            // A symlink out of the home would otherwise search the SD card.
            if (!canonical.equals(home) && !canonical.startsWith(home + File.separator)) continue;
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) pending.addLast(child);
                }
            } else if (file.getName().toLowerCase().contains(needle)) {
                addDocumentRow(cursor, file);
            }
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parent = new File(parentDocumentId).getCanonicalPath();
            String child = new File(documentId).getCanonicalPath();
            return child.equals(parent) || child.startsWith(parent + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A document id is the path relative to the app's home behind an opaque
     * prefix. Absolutes were the obvious choice, but a relative id cannot name
     * a file outside the home even if a caller edits it.
     */
    private static String idFor(File file) {
        String base = HOME.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(base)) return ID_PREFIX;
        if (path.startsWith(base + File.separator)) return ID_PREFIX + path.substring(base.length() + 1);
        return ID_PREFIX + path;
    }

    private static File fileFor(String documentId) throws FileNotFoundException {
        String relative = documentId != null && documentId.startsWith(ID_PREFIX)
            ? documentId.substring(ID_PREFIX.length())
            : documentId;
        File file = new File(HOME, relative == null ? "" : relative);
        try {
            String base = HOME.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) {
                throw new FileNotFoundException(documentId + " is outside the home");
            }
        } catch (IOException e) {
            throw new FileNotFoundException(documentId + " is not reachable");
        }
        if (!file.exists()) throw new FileNotFoundException(documentId + " not found");
        return file;
    }

    private static String mimeFor(File file) {
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static void addDocumentRow(MatrixCursor cursor, File file) {
        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        File parent = file.getParentFile();
        if (parent != null && parent.canWrite()) flags |= Document.FLAG_SUPPORTS_DELETE;

        String mime = mimeFor(file);
        if (mime.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, idFor(file));
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, mime);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }
}
