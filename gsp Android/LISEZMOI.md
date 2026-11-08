# GestSP Mobile — version Android simplifiée

Application Android (Android 10 et plus) pour consulter et alimenter le SharePoint **CEA Collab** depuis un
téléphone. Elle reprend l'essentiel de GestSP, sans les fonctions d'administration.

| Écran | Ce qu'on y fait |
|---|---|
| **Accueil** | Documents à valider, dossiers favoris, bibliothèques (avec votre profil sur chacune) |
| **Parcourir** | Naviguer dans les dossiers (fil d'Ariane), ★ favoris, créer un dossier, bouton **+** pour ajouter des fichiers ou **prendre une photo** directement dans le dossier, avec indicateurs |
| **Fiche d'un document** | Taille, version, statut, relecteur, échéance, indicateurs ; **Ouvrir** (téléchargé puis ouvert avec l'application du téléphone), **Partager le lien** (courriel, Teams, SMS…), **Indicateurs**, **Valider / Refuser** si vous êtes le relecteur |
| **Rechercher** | Par nom et indicateurs (`#facture`, `-brouillon`) ou dans le contenu (recherche SharePoint) |
| **À valider** | Les documents en relecture dont vous êtes le relecteur |
| **Réglages** | Utilisateur, site, profil par bibliothèque, changer de site, se déconnecter |

Captures (mode démo) : `docs/1_accueil.png` à `docs/6_ajout.png`.

**Non repris sur mobile :** suppression, déplacement, gestion des droits, archivage d'un dossier du PC, versions,
conservation, tableau de bord. Ces fonctions restent dans GestSP sous Windows.

## Fonctionnement et sécurité
- La connexion se fait sur **la vraie page de connexion du site** (identifiant I2I). L'application ne voit ni ne
  stocke le mot de passe : seule la session SharePoint est gardée par le téléphone, comme dans un navigateur.
- Ensuite, l'interface mobile (`app/src/main/assets/app.html`) interroge l'API REST de SharePoint avec cette session.
  Les **droits SharePoint** s'appliquent : un profil Lecteur ne voit ni le bouton +, ni l'édition des indicateurs.
- Si GestSP sous Windows a **partagé ses réglages sur le site** (Paramètres › Profils et partage), l'application
  reprend la même liste d'indicateurs (avec les couleurs) et le même tableau des profils.
- Les documents ouverts sont enregistrés dans `Téléchargements/GestSP`, les photos prises dans `Images/GestSP`.
- Certificat interne du CEA : si le téléphone ne le reconnaît pas, installez l'autorité de certification
  (Paramètres › Sécurité › Chiffrement et identifiants › Installer un certificat). L'application accepte les
  autorités installées par l'utilisateur.

## Construire l'APK

Le projet n'a **aucune dépendance externe** (SDK Android et Kotlin seulement).

### Option 1 — sur un PC Windows avec Android Studio
1. Installer Android Studio (https://developer.android.com/studio) et l'ouvrir une fois (il installe le SDK).
2. Double-cliquer `construire_apk.bat` → `GestSP-Mobile.apk`.
   Ou : Android Studio › Open › dossier `android` › Build › Build APK(s).

### Option 2 — sans rien installer, avec GitHub
1. Déposer le dossier GestSP dans un dépôt GitHub (privé de préférence).
2. Le fichier `.github/workflows/android.yml` construit l'APK automatiquement (onglet **Actions**).
3. Télécharger l'artefact **GestSP-Mobile-apk**.

### Installer sur le téléphone
Copier `GestSP-Mobile.apk` sur le téléphone, l'ouvrir et autoriser « Installer des applications inconnues »
pour le gestionnaire de fichiers. Au premier lancement, confirmer l'adresse du site (`https://aaccea.cea.fr` par
défaut), puis se connecter.

L'APK est signé avec une clé de développement : il convient pour une diffusion interne. Pour une diffusion par le
Play Store ou par la flotte mobile du CEA (MDM), il faudra le signer avec une clé de l'organisation.

## Tester sans téléphone
```
python tests/mock_sp_server.py 8765        # faux SharePoint + application mobile
```
puis ouvrir http://127.0.0.1:8765/ dans Chrome en mode « appareil mobile » (F12 › icône téléphone).
Tests automatiques : `python -m pytest tests/test_mobile.py` (Chromium au format Pixel 7).

## Structure
```
android/
  app/src/main/java/fr/gestsp/mobile/MainActivity.kt   connexion, WebView, téléchargements, photos, partage
  app/src/main/assets/app.html                         interface mobile (HTML/JS, sans bibliothèque)
  app/src/main/AndroidManifest.xml, res/                icônes, thème, certificats
  construire_apk.bat                                    construction sous Windows
.github/workflows/android.yml                           construction automatique sur GitHub
```
