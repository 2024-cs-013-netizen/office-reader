private String getFileName(Uri uri, String mime) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
            if (result == null) result = "Document";
        }

        String lower = result.toLowerCase();
        if (!lower.contains(".")) {
            if (mime.contains("opendocument.text")) result += ".odt";
            else if (mime.contains("opendocument.spreadsheet")) result += ".ods";
            else if (mime.contains("opendocument.presentation")) result += ".odp";
            else if (mime.contains("csv") || mime.contains("comma-separated")) result += ".csv";
            else if (mime.contains("word")) result += ".docx";
            else if (mime.contains("sheet") || mime.contains("excel")) result += ".xlsx";
            else if (mime.contains("presentation") || mime.contains("powerpoint")) result += ".pptx";
            else if (mime.contains("pdf")) result += ".pdf";
            else if (mime.contains("rtf")) result += ".rtf";
            else if (mime.contains("json")) result += ".json";
            else if (mime.contains("xml")) result += ".xml";
            else if (mime.contains("markdown")) result += ".md";
            else if (mime.contains("text/plain")) result += ".txt";
        }
        return result;
    }
