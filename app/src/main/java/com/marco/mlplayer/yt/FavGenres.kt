package com.marco.mlplayer.yt

/** I generi che si possono scegliere come preferiti (Benvenuto e Impostazioni): servono alla Home per proporre musica più adatta. */
object FavGenres {
    class Genre(val id: String, val label: String, val query: String)

    val ALL = listOf(
        Genre("pop", "Pop", "Pop hits"),
        Genre("pop-it", "Pop italiano", "Pop italiano hits"),
        Genre("rap-it", "Rap italiano", "Rap italiano"),
        Genre("cantautori", "Cantautori", "Cantautori italiani"),
        Genre("rock", "Rock", "Rock hits"),
        Genre("indie", "Indie", "Indie"),
        Genre("hiphop", "Hip-hop", "Hip hop hits"),
        Genre("trap", "Trap", "Trap"),
        Genre("rnb", "R&B", "R&B hits"),
        Genre("dance", "Dance", "Dance hits"),
        Genre("electronic", "Elettronica", "Musica elettronica"),
        Genre("house", "House", "House music"),
        Genre("techno", "Techno", "Techno"),
        Genre("reggaeton", "Reggaeton e latino", "Reggaeton latino hits"),
        Genre("metal", "Metal", "Metal"),
        Genre("punk", "Punk", "Punk rock"),
        Genre("jazz", "Jazz", "Jazz"),
        Genre("blues", "Blues", "Blues"),
        Genre("classica", "Classica", "Musica classica"),
        Genre("soul", "Soul e funk", "Soul funk"),
        Genre("reggae", "Reggae", "Reggae"),
        Genre("country", "Country", "Country hits"),
        Genre("folk", "Folk e acustica", "Folk acoustic"),
        Genre("kpop", "K-pop", "K-pop"),
        Genre("anni", "Anni '80 e '90", "Hit anni 80 90"),
        Genre("lofi", "Lo-fi e relax", "Lofi chill"),
        Genre("colonne", "Colonne sonore", "Colonne sonore"),
    )

    /** Gli id scelti, dalla preferenza salvata (separati da virgola); quelli sconosciuti si ignorano. */
    fun parse(csv: String): List<Genre> {
        val ids = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return ALL.filter { it.id in ids }
    }

    fun toggle(csv: String, id: String): String {
        val ids = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (!ids.remove(id)) ids.add(id)
        return ids.joinToString(",")
    }
}
