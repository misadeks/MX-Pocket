package tel.misa.mxpocket.i18n

internal object SavedEditorCopy {
    val NoProfiles = Copy("No saved profiles to show. Create one or enable a plugin.",
        "Aucun profil enregistré à afficher. Créez un profil ou activez une extension.",
        "Nema sačuvanih profila za prikaz. Napravite profil ili omogućite dodatak.")
    val PageCount = PluralCopy(
        Copy("{0} page", "{0} page", "{0} stranica"),
        Copy("{0} pages", "{0} pages", "{0} stranice"),
        Copy("{0} pages", "{0} pages", "{0} stranica"))
    val AllProfiles = Copy("All profiles", "Tous les profils", "Svi profili")
    val Pages = Copy("Pages", "Pages", "Stranice")
    val RootPage = Copy("Root page", "Page racine", "Početna stranica")
    val BackTo = Copy("Back to {0}", "Retour à « {0} »", "Nazad na „{0}”")
    val BackToPages = Copy("Back to {0} pages", "Retour aux pages de « {0} »", "Nazad na stranice profila „{0}”")
    val Selected = Copy("Selected for keypad", "Profil actif sur le clavier", "Izabrano za tastaturu")
    val UseOnKeypad = Copy("Use on keypad", "Utiliser sur le clavier", "Koristi na tastaturi")
}
