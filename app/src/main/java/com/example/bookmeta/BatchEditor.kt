package com.example.bookmeta

object BatchEditor {
    /** Applies only non-empty values; optionally preserves values already present in each book. */
    fun apply(source: BookMeta, target: BookMeta, fillOnlyEmpty: Boolean): BookMeta {
        fun choose(old: String, new: String) = if (new.isBlank() || (fillOnlyEmpty && old.isNotBlank())) old else new
        return target.copy(
            title=choose(target.title,source.title), author=choose(target.author,source.author),
            description=choose(target.description,source.description), publisher=choose(target.publisher,source.publisher),
            year=choose(target.year,source.year), isbn=choose(target.isbn,source.isbn), language=choose(target.language,source.language),
            subjects=choose(target.subjects,source.subjects), series=choose(target.series,source.series),
            seriesNumber=choose(target.seriesNumber,source.seriesNumber), coverUri=choose(target.coverUri,source.coverUri)
        )
    }
}
