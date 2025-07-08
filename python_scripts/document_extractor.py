import sys
import json
import re
import unicodedata
from typing import Dict, Optional

# Imports avec gestion d'erreurs
try:
    from passporteye import read_mrz
    PASSPORT_EYE_AVAILABLE = True
except ImportError:
    PASSPORT_EYE_AVAILABLE = False

try:
    import easyocr
    EASYOCR_AVAILABLE = True
except ImportError:
    EASYOCR_AVAILABLE = False

def normalize_text(text):
    """Normalise le texte"""
    text = unicodedata.normalize('NFD', text)
    text = ''.join(char for char in text if unicodedata.category(char) != 'Mn')
    return text.upper().strip()

def detect_document_type(image_path: str) -> str:
    """Détection rapide du type de document"""
    if not EASYOCR_AVAILABLE:
        return 'PASSPORT'  # Par défaut

    try:
        reader = easyocr.Reader(['en', 'fr'], gpu=False)
        results = reader.readtext(image_path)
        text = ' '.join([r[1] for r in results if r[2] > 0.4])
        text_norm = normalize_text(text)

        if any(kw in text_norm for kw in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE DU']):
            return 'PASSPORT'
        elif any(kw in text_norm for kw in ['CARTE ETUDIANT', 'STUDENT CARD']):
            return 'STUDENT_CARD'
        elif any(kw in text_norm for kw in ['CARTE IDENTITE', 'IDENTITY CARD']):
            return 'ID_CARD'
        else:
            return 'UNKNOWN'
    except:
        return 'PASSPORT'

def format_date(date_str: str) -> str:
    """Convertit date MRZ YYMMDD en DD/MM/YYYY"""
    if len(date_str) == 6 and date_str.isdigit():
        yy, mm, dd = date_str[:2], date_str[2:4], date_str[4:6]
        year = int(yy)
        full_year = 2000 + year if year <= 30 else 1900 + year
        return f"{dd}/{mm}/{full_year}"
    return date_str

def parse_month_name(month_text: str) -> str:
    """Convertit un nom de mois (même déformé par OCR) en numéro"""
    month_mapping = {
        # Français
        'JANVIER': '01', 'JANV': '01', 'JAN': '01',
        'FEVRIER': '02', 'FEVR': '02', 'FEV': '02',
        'MARS': '03', 'MAR': '03',
        'AVRIL': '04', 'AVR': '04', 'APR': '04',
        'MAI': '05', 'MAY': '05',
        'JUIN': '06', 'JUN': '06',

        # Juillet - Variations OCR courantes pour "JUIL/JUL"
        'JUILLET': '07', 'JUIL': '07', 'JUL': '07',
        'JUILJUL': '07',      # OCR lit "JUIL/JUL" comme "JUILJUL"
        'JUIL/JUL': '07',     # Si OCR garde le slash
        'JUIL-JUL': '07',     # Si OCR lit le slash comme tiret
        'JUIL JUL': '07',     # Si OCR lit le slash comme espace
        'JUILLET/JUL': '07',  # Variations mixtes

        'AOUT': '08', 'AOU': '08', 'AUG': '08',

        # Septembre - Variations OCR courantes pour "SEPT/SEP"
        'SEPTEMBRE': '09', 'SEPT': '09', 'SEP': '09',
        'SEPTISEP': '09',     # OCR lit "SEPT/SEP" comme "SEPTISEP"
        'SEPTSEP': '09',      # Variation possible
        'SEPT/SEP': '09',     # Si OCR garde le slash
        'SEPT-SEP': '09',     # Si OCR lit le slash comme tiret
        'SEPT SEP': '09',     # Si OCR lit le slash comme espace
        'SEPTEMBRE/SEP': '09', # Variations mixtes

        'OCTOBRE': '10', 'OCT': '10', 'OCTB': '10',
        'NOVEMBRE': '11', 'NOV': '11',
        'DECEMBRE': '12', 'DEC': '12',

        # Anglais
        'JANUARY': '01', 'FEBRUARY': '02', 'MARCH': '03',
        'APRIL': '04', 'JUNE': '06', 'JULY': '07',
        'AUGUST': '08', 'SEPTEMBER': '09', 'OCTOBER': '10',
        'NOVEMBER': '11', 'DECEMBER': '12'
    }

    month_upper = month_text.upper().strip()
    return month_mapping.get(month_upper, month_text)

def parse_date_with_month_name(date_text: str) -> str:
    """Parse une date avec nom de mois en format DD/MM/YYYY"""
    # Pattern pour capturer jour, mois (nom), année
    pattern = r'(\d{1,2})\s+([A-Z]+)\s+(\d{4})'
    match = re.search(pattern, date_text.upper())

    if match:
        day, month_name, year = match.groups()
        month_num = parse_month_name(month_name)

        # Si le mois a été reconnu, formatter la date
        if month_num.isdigit():
            return f"{day.zfill(2)}/{month_num}/{year}"

    return date_text

def get_country_info(code: str) -> Dict[str, str]:
    """Mapping codes pays"""
    countries = {
        'FRA': {'name': 'FRANCE', 'nationality': 'FRENCH'},
        'SEN': {'name': 'SENEGAL', 'nationality': 'SENEGALESE'},
        'DEU': {'name': 'GERMANY', 'nationality': 'GERMAN'},
        'ESP': {'name': 'SPAIN', 'nationality': 'SPANISH'},
        'EOL': {'name': 'REPUBLIC OF EOLIE', 'nationality': 'EOLIAN'},
    }
    return countries.get(code.upper(), {'name': code, 'nationality': code})

def extract_passport(image_path: str) -> Dict:
    """Extraction passeport avec PassportEye"""
    if not PASSPORT_EYE_AVAILABLE:
        return {'status': 'error', 'error': 'PassportEye non disponible'}

    try:
        mrz_data = read_mrz(image_path)
        if not mrz_data:
            return {'status': 'error', 'error': 'Aucune MRZ détectée'}

        # Extraction données MRZ
        data = {}

        if hasattr(mrz_data, 'number') and mrz_data.number:
            # Correction O/0
            num = mrz_data.number.replace('O', '0').replace('o', '0')
            data['documentNumbers'] = [num]

        if hasattr(mrz_data, 'surname') and mrz_data.surname:
            data['surname'] = mrz_data.surname

        if hasattr(mrz_data, 'names') and mrz_data.names:
            # Nettoyage prénoms
            names = mrz_data.names.strip()
            names = re.sub(r'([A-Z])\1{3,}', '', names)  # KKKK -> vide
            names = re.sub(r'RK\b', 'R', names)  # MOCKTARK -> MOCKTAR
            data['givenNames'] = names.strip()

        if hasattr(mrz_data, 'sex') and mrz_data.sex:
            data['sex'] = mrz_data.sex

        if hasattr(mrz_data, 'nationality') and mrz_data.nationality:
            country_info = get_country_info(mrz_data.nationality)
            data['nationality'] = country_info['nationality']

        # Dates avec distinction de type
        if hasattr(mrz_data, 'date_of_birth') and mrz_data.date_of_birth:
            if isinstance(mrz_data.date_of_birth, str):
                data['dateOfBirth'] = format_date(mrz_data.date_of_birth)
            else:
                data['dateOfBirth'] = mrz_data.date_of_birth.strftime("%d/%m/%Y")

        if hasattr(mrz_data, 'expiration_date') and mrz_data.expiration_date:
            if isinstance(mrz_data.expiration_date, str):
                data['expiryDate'] = format_date(mrz_data.expiration_date)
            else:
                data['expiryDate'] = mrz_data.expiration_date.strftime("%d/%m/%Y")

        # Extraction complémentaire EasyOCR pour données non-MRZ
        if EASYOCR_AVAILABLE:
            try:
                reader = easyocr.Reader(['en', 'fr'], gpu=False)
                results = reader.readtext(image_path)
                text_blocks = [r[1] for r in results if r[2] > 0.5]
                text_combined = ' '.join(text_blocks)

                # Lieu de naissance - Approche générique avec filtrage amélioré
                birth_place_patterns = [
                    r'(?:LIEU DE NAISSANCE|PLACE OF BIRTH|NE A)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s|$)',
                    r'(?:NE A|BORN IN)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s|$)',
                ]

                birth_place = None

                # D'abord essayer les patterns avec labels (plus fiables)
                for pattern in birth_place_patterns:
                    match = re.search(pattern, text_combined, re.IGNORECASE)
                    if match:
                        candidate = match.group(1).strip()
                        if len(candidate) >= 3 and not re.search(r'\d', candidate):
                            birth_place = candidate
                            break

                # Si pas trouvé, utiliser l'heuristique générique avec filtrage strict
                if not birth_place:
                    # Chercher des mots en majuscules qui pourraient être des villes
                    potential_cities = re.findall(r'\b([A-Z]{3,}(?:\s+[A-Z]{3,})*)\b', text_combined)

                    for candidate in potential_cities:
                        candidate = candidate.strip()

                        # Filtrage strict : exclure tout ce qui n'est pas une ville
                        excluded_words = [
                            # Mots du document
                            'PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'SENEGAL', 'FRANCE', 'SERVICE',
                            'AUTORITE', 'PREFECTURE', 'MINISTERE', 'SENEGALAISE', 'FRANCAISE',

                            # Mois en français et anglais (erreurs OCR courantes)
                            'JANVIER', 'FEVRIER', 'MARS', 'AVRIL', 'MAI', 'JUIN',
                            'JUILLET', 'AOUT', 'SEPTEMBRE', 'OCTOBRE', 'NOVEMBRE', 'DECEMBRE',
                            'JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'JUNE', 'JULY',
                            'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER',

                            # Erreurs OCR typiques des mois
                            'JUILJUL', 'JUIL', 'SEPT', 'JANV', 'FEVR', 'MARS', 'OCTB', 'OCT', 'NOV', 'DEC',
                            'SEPTISEP',

                            # Autres mots communs
                            'MASCULINE', 'FEMININE', 'MALE', 'FEMALE', 'BORN', 'NAISSANCE'
                        ]

                        # Vérifier que c'est potentiellement une ville
                        if (len(candidate) >= 3 and
                                candidate.upper() not in excluded_words and
                                not re.search(r'\d', candidate) and  # Pas de chiffres
                                not re.search(r'[/\-:]', candidate) and  # Pas de séparateurs de date
                                candidate.isalpha() and  # Que des lettres
                                len(candidate) <= 20):  # Pas trop long (éviter les phrases)

                            # Vérifier que ce n'est pas dans une date
                            date_context = re.search(rf'\d{{1,2}}\s+{re.escape(candidate)}\s+\d{{4}}', text_combined)
                            if not date_context:
                                birth_place = candidate
                                break

                if birth_place:
                    data['birthPlace'] = birth_place

                # Autorité de délivrance - Approche générale et robuste
                text_normalized = normalize_text(text_combined)
                issuing_authority = None

                # === ÉTAPE 1: Patterns avec labels explicites (plus fiables) ===
                authority_patterns_with_labels = [
                    r'(?:AUTORITE|AUTHORITY|DELIVRE PAR|ISSUED BY|ISSUING AUTHORITY)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s|$)',
                    r'(?:EMIS PAR|ISSUED BY|DELIVERED BY)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s|$)',
                ]

                for i, pattern in enumerate(authority_patterns_with_labels):
                    match = re.search(pattern, text_normalized, re.IGNORECASE)
                    if match:
                        candidate = match.group(1).strip()
                        if len(candidate) >= 3:
                            issuing_authority = candidate
                            break

                # === ÉTAPE 2: Patterns d'autorités administratives connues ===
                if not issuing_authority:
                    authority_patterns_admin = [
                        # Ministères et départements
                        r'\b((?:MINISTERE|MINISTRY)\s+[A-Z\s]+?)(?:\s|$)',
                        r'\b((?:DEPARTMENT|DEPT)\s+[A-Z\s]+?)(?:\s|$)',
                        r'\b([A-Z]+\s+(?:MINISTRY|MINISTERE))\b',

                        # Préfectures et bureaux
                        r'\b((?:PREFECTURE|SOUS-PREFECTURE)\s+[A-Z\s]+?)(?:\s|$)',
                        r'\b((?:PASSPORT\s+OFFICE|BUREAU\s+DES\s+PASSEPORTS))\b',
                        r'\b([A-Z]+\s+(?:OFFICE|BUREAU))\b',

                        # Républiques avec "DU/DE/OF"
                        r'\b(REPUBLIQUE\s+DU\s+[A-Z]+)\b',              # REPUBLIQUE DU SENEGAL
                        r'\b(REPUBLIQUE\s+DE\s+[A-Z]+)\b',              # REPUBLIQUE DE FRANCE (rare)
                        r'\b(REPUBLIC\s+OF\s+[A-Z]+)\b',                # REPUBLIC OF FRANCE

                        # Républiques sans préposition (plus courant)
                        r'\b(REPUBLIQUE\s+[A-Z]{8,})\b',                # REPUBLIQUE FRANCAISE, REPUBLIQUE SENEGALAISE
                        r'\b([A-Z]+\s+REPUBLIC)\b',                     # FRENCH REPUBLIC, GERMAN REPUBLIC

                        # Fédérations et autres formes
                        r'\b(REPUBLIQUE\s+FEDERALE\s+[A-Z\s]+)\b',      # REPUBLIQUE FEDERALE D'ALLEMAGNE
                        r'\b(FEDERAL\s+REPUBLIC\s+OF\s+[A-Z]+)\b',      # FEDERAL REPUBLIC OF GERMANY
                        r'\b([A-Z]+\s+FEDERATION)\b',                   # RUSSIAN FEDERATION

                        # Royaumes et autres formes politiques
                        r'\b(KINGDOM\s+OF\s+[A-Z]+)\b',                 # KINGDOM OF SPAIN
                        r'\b(ROYAUME\s+DU\s+[A-Z]+)\b',                 # ROYAUME DU MAROC
                        r'\b(UNITE[SD]?\s+KINGDOM)\b',                  # UNITED KINGDOM
                        r'\b(ROYAUME\s+UNI)\b',                         # ROYAUME UNI

                        # Autorités spéciales (Allemagne, etc.)
                        r'\b(BUNDESREPUBLIK\s+[A-Z]+)\b',               # BUNDESREPUBLIK DEUTSCHLAND
                        r'\b([A-Z]{10,})\b',                            # Mots très longs type BUNDESDRUCKEREI
                    ]

                    for i, pattern in enumerate(authority_patterns_admin):
                        match = re.search(pattern, text_normalized, re.IGNORECASE)
                        if match:
                            candidate = match.group(1).strip()

                            # Filtrage basique
                            if (len(candidate) >= 5 and
                                    not re.search(r'\d', candidate)):
                                issuing_authority = candidate
                                break

                # === ÉTAPE 3: Recherche d'acronymes et autorités courtes ===
                if not issuing_authority:
                    # Trouver tous les mots de 3-10 lettres en majuscules
                    potential_authorities = re.findall(r'\b([A-Z]{3,10})\b', text_normalized)

                    # Liste d'exclusion étendue
                    excluded_words = {
                        # Mots du document
                        'SERVICE', 'PASSPORT', 'PASSEPORT', 'SENEGAL', 'FRANCE', 'REPUBLIQUE',
                        # Données personnelles
                        'MBAYE', 'HAMADOU', 'MOCKTAR', 'SENEGALAISE', 'FRANCAISE', 'MASCULINE', 'FEMININE',
                        # Mois et erreurs OCR
                        'JANVIER', 'FEVRIER', 'MARS', 'AVRIL', 'JUIN', 'JUILLET', 'AOUT', 'SEPTEMBRE', 'OCTOBRE', 'NOVEMBRE', 'DECEMBRE',
                        'JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'JUNE', 'JULY', 'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER',
                        'SEPTISEP', 'JUILJUL', 'JANV', 'FEVR', 'AVRI', 'JUIL', 'SEPT', 'OCTB', 'NOVE', 'DECE',
                        # Villes communes
                        'KAOLACK', 'DAKAR', 'PARIS', 'LONDON', 'BERLIN', 'MADRID', 'ROME', 'LYON', 'LILLE',
                        # Autres mots communs
                        'MALE', 'FEMALE', 'BORN', 'NAISSANCE', 'PLACE', 'LIEU', 'HEIGHT', 'TAILLE'
                    }

                    # Scores contextuels pour prioriser
                    authority_candidates = []

                    for word in potential_authorities:
                        if word.upper() not in excluded_words and not re.search(r'\d', word):
                            # Calculer un score de probabilité
                            score = 0

                            # Bonus si c'est un acronyme probable d'autorité
                            if len(word) >= 3 and len(word) <= 8:
                                score += 2

                            # Bonus si contient des lettres typiques d'autorités
                            if any(letter in word for letter in ['M', 'A', 'E', 'S']):  # MAESE, MINISTRY, etc.
                                score += 1

                            # Bonus si pas trop fréquent dans le texte (évite noms de personnes)
                            occurrences = text_normalized.count(word)
                            if occurrences == 1:
                                score += 2
                            elif occurrences == 2:
                                score += 1

                            # Bonus si c'est dans une position logique (ni au tout début, ni dans les noms)
                            word_position = text_normalized.find(word)
                            text_length = len(text_normalized)
                            relative_position = word_position / text_length if text_length > 0 else 0
                            if 0.3 < relative_position < 0.8:  # Milieu du document
                                score += 1

                            authority_candidates.append((word, score))

                    # Trier par score et prendre le meilleur
                    if authority_candidates:
                        authority_candidates.sort(key=lambda x: x[1], reverse=True)
                        best_authority = authority_candidates[0]

                        # Prendre seulement si score raisonnable
                        if best_authority[1] >= 3:
                            issuing_authority = best_authority[0]

                if issuing_authority:
                    data['issuingAuthority'] = issuing_authority

                # Date de délivrance - Patterns améliorés avec support des noms de mois
                issue_date_patterns = [
                    # Patterns avec labels explicites
                    r'(?:DATE DE DELIVRANCE|ISSUE DATE|DELIVERED ON|DELIVRE LE)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',
                    r'(?:EMIS LE|ISSUED ON)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',

                    # Patterns avec noms de mois
                    r'(?:DATE DE DELIVRANCE|ISSUE DATE|DELIVERED ON|DELIVRE LE)[:\s]*(\d{1,2}\s+[A-Z]+\s+\d{4})',
                    r'(?:EMIS LE|ISSUED ON)[:\s]*(\d{1,2}\s+[A-Z]+\s+\d{4})',

                    # Patterns génériques (attention aux faux positifs)
                    r'(?:LE)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',
                    r'(?:LE)[:\s]*(\d{1,2}\s+[A-Z]+\s+\d{4})',
                ]

                issue_date = None

                # D'abord essayer les patterns avec labels (plus fiables)
                for i, pattern in enumerate(issue_date_patterns):
                    match = re.search(pattern, text_combined, re.IGNORECASE)
                    if match:
                        date_candidate = match.group(1)

                        # Si la date contient des lettres, essayer de la parser
                        if re.search(r'[A-Z]', date_candidate.upper()):
                            parsed_date = parse_date_with_month_name(date_candidate)
                            if '/' in parsed_date:  # Si parsing réussi
                                issue_date = parsed_date
                                break
                        else:
                            issue_date = date_candidate
                            break

                # Si pas trouvé avec labels, chercher toutes les dates avec noms de mois
                if not issue_date:
                    # Chercher toutes les dates numériques d'abord
                    numeric_dates = re.findall(r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})', text_combined)
                    # Chercher toutes les dates avec noms de mois
                    month_name_dates = re.findall(r'(\d{1,2}\s+[A-Z]+\s+\d{4})', text_combined)

                    all_dates = numeric_dates + month_name_dates

                    if len(all_dates) >= 2:
                        # Récupérer les dates de référence
                        birth_date_str = data.get('dateOfBirth', '')
                        expiry_date_str = data.get('expiryDate', '')

                        # Convertir les dates en objets datetime pour comparaison
                        from datetime import datetime

                        def parse_date_to_datetime(date_str):
                            try:
                                if re.search(r'[A-Z]', date_str.upper()):
                                    parsed = parse_date_with_month_name(date_str)
                                    if '/' in parsed:
                                        return datetime.strptime(parsed, '%d/%m/%Y')
                                else:
                                    # Format numérique, normaliser d'abord
                                    cleaned = re.sub(r'[^\d/]', '/', date_str)
                                    return datetime.strptime(cleaned, '%d/%m/%Y')
                            except:
                                return None

                        def parse_ref_date(date_str):
                            try:
                                return datetime.strptime(date_str, '%d/%m/%Y')
                            except:
                                return None

                        birth_dt = parse_ref_date(birth_date_str)
                        expiry_dt = parse_ref_date(expiry_date_str)

                        # Chercher la date qui pourrait être la date de délivrance
                        # (entre naissance et expiration, ou proche de l'expiration)
                        best_candidate = None
                        best_candidate_str = None

                        for candidate_date in all_dates:
                            # Parser la date candidate
                            candidate_dt = parse_date_to_datetime(candidate_date)
                            if not candidate_dt:
                                continue

                            # Parser la version string de la candidate
                            if re.search(r'[A-Z]', candidate_date.upper()):
                                candidate_str = parse_date_with_month_name(candidate_date)
                            else:
                                candidate_str = candidate_date

                            # Exclure si c'est la date de naissance ou d'expiration
                            if (candidate_str == birth_date_str or
                                    candidate_str == expiry_date_str):
                                continue

                            # Logique de sélection intelligente
                            is_valid_issue_date = False

                            if birth_dt and expiry_dt:
                                # Date entre naissance et expiration = très probable
                                if birth_dt < candidate_dt < expiry_dt:
                                    is_valid_issue_date = True
                            elif birth_dt:
                                # Au moins après la naissance
                                if candidate_dt > birth_dt:
                                    is_valid_issue_date = True
                            else:
                                # Pas de référence, prendre si raisonnable (pas dans le futur lointain)
                                current_year = datetime.now().year
                                if candidate_dt.year <= current_year + 1:
                                    is_valid_issue_date = True

                            if is_valid_issue_date:
                                if not best_candidate or candidate_dt > best_candidate:
                                    # Prendre la plus récente des dates valides
                                    best_candidate = candidate_dt
                                    best_candidate_str = candidate_str

                        if best_candidate_str:
                            issue_date = best_candidate_str

                # Formatage de la date de délivrance
                if issue_date:
                    def format_issue_date(date_str):
                        # Si déjà au bon format, retourner tel quel
                        if re.match(r'\d{2}/\d{2}/\d{4}', date_str):
                            return date_str

                        cleaned = re.sub(r'[^\d/]', '/', date_str)
                        parts = cleaned.split('/')
                        if len(parts) == 3:
                            day, month, year = parts
                            if len(year) == 2:
                                year_int = int(year)
                                year = str(2000 + year_int if year_int <= 30 else 1900 + year_int)
                            return f"{day.zfill(2)}/{month.zfill(2)}/{year}"
                        return date_str

                    data['issueDate'] = format_issue_date(issue_date)

                # Taille
                height_match = re.search(r'(\d{1}[,\.]\d{2})\s*[mM]', text_combined)
                if height_match:
                    height = height_match.group(1).replace(',', '.')
                    data['height'] = f"{height}m"

            except Exception as e:
                pass  # Si extraction complémentaire échoue, on continue

        # Pays émetteur
        issuing_country = 'UNKNOWN'
        if hasattr(mrz_data, 'country') and mrz_data.country:
            country_info = get_country_info(mrz_data.country)
            issuing_country = country_info['name']

        return {
            'status': 'success',
            'documentType': 'PASSPORT',
            'issuingCountry': issuing_country,
            'confidence': 'very_high' if getattr(mrz_data, 'valid', False) else 'high',
            'data': data,
            'extractionMethod': 'PassportEye',
            'mrzDetected': True,
            'mrzValid': getattr(mrz_data, 'valid', False)
        }

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

def extract_card(image_path: str, doc_type: str) -> Dict:
    """Extraction cartes avec EasyOCR - Approche séquentielle"""
    if not EASYOCR_AVAILABLE:
        return {'status': 'error', 'error': 'EasyOCR non disponible'}

    try:
        reader = easyocr.Reader(['en', 'fr'], gpu=False)
        results = reader.readtext(image_path)
        text_blocks = [r[1] for r in results if r[2] > 0.5]

        if not text_blocks:
            return {'status': 'error', 'error': 'Aucun texte détecté'}

        data = {}
        text_combined = ' '.join(text_blocks)

        # 1. Extraction noms - Approche séquentielle pour cartes étudiantes
        if doc_type == 'STUDENT_CARD':
            for i, block in enumerate(text_blocks):
                if 'CARTE' in normalize_text(block) and 'ETUDIANT' in normalize_text(block):
                    # Prénom (bloc suivant, format mixte)
                    if i + 1 < len(text_blocks):
                        next_block = text_blocks[i + 1].strip()
                        if next_block.isalpha() and not next_block.isupper() and len(next_block) >= 2:
                            data['givenNames'] = next_block.title()

                    # Nom (bloc d'après, MAJUSCULES)
                    if i + 2 < len(text_blocks):
                        surname_block = text_blocks[i + 2].strip()
                        if surname_block.isupper() and len(surname_block) >= 3:
                            # Filtrer les mots-clés
                            if not any(kw in surname_block for kw in ['CARTE', 'STUDENT', 'ECE', 'PARIS']):
                                data['surname'] = surname_block
                    break

        # Autres types : patterns simples
        else:
            # Noms avec labels
            surname_match = re.search(r'(?:NOM|SURNAME)[:\s]*([A-Z][A-Z\s]+)', text_combined, re.IGNORECASE)
            given_match = re.search(r'(?:PRENOM|GIVEN NAME)[:\s]*([A-Z][A-Z\s]+)', text_combined, re.IGNORECASE)

            if surname_match:
                data['surname'] = surname_match.group(1).strip()
            if given_match:
                data['givenNames'] = given_match.group(1).strip()

        # 2. Dates avec distinction de type
        birth_date = None
        expiry_date = None

        # Patterns spécifiques avec labels
        birth_patterns = [
            r'(?:NE|BORN|NAISSANCE|DATE DE NAISSANCE)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',
            r'(?:NE\(E\)\s+LE)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})'
        ]

        # Chercher date de naissance avec label
        for pattern in birth_patterns:
            match = re.search(pattern, text_combined, re.IGNORECASE)
            if match:
                birth_date = match.group(1)
                break

        # Si pas trouvé avec labels, prendre les dates génériques
        if not birth_date:
            date_matches = re.findall(r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})', text_combined)
            if date_matches:
                # Pour cartes étudiantes, généralement 1 seule date = naissance
                if doc_type == 'STUDENT_CARD' and len(date_matches) >= 1:
                    birth_date = date_matches[0]
                elif len(date_matches) >= 1:
                    birth_date = date_matches[0]

        # Formatage des dates
        def format_card_date(date_str):
            if not date_str:
                return None
            cleaned = re.sub(r'[^\d/]', '/', date_str)
            parts = cleaned.split('/')
            if len(parts) == 3:
                day, month, year = parts
                if len(year) == 2:
                    year_int = int(year)
                    year = str(2000 + year_int if year_int <= 30 else 1900 + year_int)
                return f"{day.zfill(2)}/{month.zfill(2)}/{year}"
            return date_str

        if birth_date:
            data['dateOfBirth'] = format_card_date(birth_date)
        if expiry_date:
            data['expiryDate'] = format_card_date(expiry_date)

        # 3. Numéros de document - Extraction améliorée
        numbers = []

        # Fonction de validation
        def is_valid_number(num_str):
            forbidden_words = [
                'ETUDIANT', 'STUDENT', 'CARTE', 'CARD', 'BORN', 'NAISSANCE',
                'ECE', 'PARIS', 'FRANCE', 'REPUBLIQUE', 'INGENIEUR', 'SCHOOL'
            ]
            if num_str.upper() in forbidden_words:
                return False
            if re.match(r'^\d{2}/\d{2}/\d{4}$', num_str) or re.match(r'^\d{4}$', num_str):
                return False
            if num_str.isalpha() and len(num_str) > 6:
                return False
            return True

        # Patterns pour cartes étudiantes
        if doc_type == 'STUDENT_CARD':
            number_patterns = [
                r'\b(\d{9}[A-Z]{2,3})\b',        # Format INE: 233133124HB
                r'\b(\d{8,12})\b',               # Numéros longs: 932354782
            ]
        else:
            number_patterns = [
                r'\b([A-Z]{1,3}\d{6,12})\b',     # Format type FR123456789
                r'\b(\d{8,15})\b',               # Numéros longs
            ]

        # Extraction avec validation
        for pattern in number_patterns:
            matches = re.finditer(pattern, text_combined)
            for match in matches:
                num = match.group(1)
                if is_valid_number(num) and len(num) >= 6:
                    numbers.append(num)

        # Supprimer doublons
        unique_numbers = []
        for num in numbers:
            if num not in unique_numbers:
                unique_numbers.append(num)

        if unique_numbers:
            data['documentNumbers'] = unique_numbers[:3]

        # 4. Sexe
        text_norm = normalize_text(text_combined)
        if re.search(r'\b(M|MALE|MASCULIN)\b', text_norm):
            data['sex'] = 'M'
        elif re.search(r'\b(F|FEMALE|FEMININ)\b', text_norm):
            data['sex'] = 'F'

        # 5. Pays
        issuing_country = 'UNKNOWN'
        if any(kw in text_norm for kw in ['FRANCE', 'REPUBLIQUE FRANCAISE']):
            issuing_country = 'FRANCE'

        return {
            'status': 'success',
            'documentType': doc_type,
            'issuingCountry': issuing_country,
            'confidence': 'medium',
            'data': data,
            'extractionMethod': 'EasyOCR'
        }

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

def extract_document_data(image_path: str) -> Dict:
    """Fonction principale - Stratégie adaptative"""
    try:
        # 1. Détection type
        doc_type = detect_document_type(image_path)

        # 2. Extraction selon le type
        if doc_type == 'PASSPORT':
            return extract_passport(image_path)
        else:
            return extract_card(image_path, doc_type)

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(json.dumps({'error': 'Usage: python document_extractor.py <image_path>'}))
        sys.exit(1)

    image_path = sys.argv[1]
    result = extract_document_data(image_path)
    print(json.dumps(result, ensure_ascii=False, indent=2))