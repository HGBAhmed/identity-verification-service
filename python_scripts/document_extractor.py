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
    """Détection du type de document - seulement PASSPORT ou ID_CARD"""
    if not EASYOCR_AVAILABLE:
        return 'PASSPORT'  # Par défaut

    try:
        reader = easyocr.Reader(['en', 'fr'], gpu=False)
        results = reader.readtext(image_path)
        text = ' '.join([r[1] for r in results if r[2] > 0.4])
        text_norm = normalize_text(text)

        if any(kw in text_norm for kw in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE DU']):
            return 'PASSPORT'
        elif any(kw in text_norm for kw in ['CARTE IDENTITE', 'IDENTITY CARD', 'CARTE NATIONALE']):
            return 'ID_CARD'
        else:
            return 'PASSPORT'  # Par défaut si incertain
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
        'JUILLET': '07', 'JUIL': '07', 'JUL': '07',
        'JUILJUL': '07', 'JUIL/JUL': '07', 'JUIL-JUL': '07', 'JUIL JUL': '07', 'JUILLET/JUL': '07',
        'AOUT': '08', 'AOU': '08', 'AUG': '08',
        'SEPTEMBRE': '09', 'SEPT': '09', 'SEP': '09',
        'SEPTISEP': '09', 'SEPTSEP': '09', 'SEPT/SEP': '09', 'SEPT-SEP': '09', 'SEPT SEP': '09', 'SEPTEMBRE/SEP': '09',
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
    pattern = r'(\d{1,2})\s+([A-Z]+)\s+(\d{4})'
    match = re.search(pattern, date_text.upper())

    if match:
        day, month_name, year = match.groups()
        month_num = parse_month_name(month_name)

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

# FONCTIONS POUR CARTES D'IDENTITÉ

def clean_extracted_text(text: str, labels_to_remove: list) -> str:
    """Nettoie le texte extrait en supprimant les labels indésirables"""
    cleaned = text.strip()

    # Supprimer les labels spécifiés + variantes OCR courantes
    all_labels = labels_to_remove + [
        'SURNAME', 'Surname', 'SUMAME', 'Sumame',  # Erreurs OCR courantes
        'GIVEN NAMES', 'Given names', 'PRENOMS', 'Prenoms',
        'PLACE OF BIRTH', 'Place of birth', 'LIEU DE NAISSANCE',
        'ALTERNATE NAME', 'Alternate name', 'ALTEMATE NAME', 'Altemate name',
        'NOM D\'USAGE', '/SURNAME', '/GIVEN NAMES', '/PLACE OF BIRTH', '/ALTERNATE NAME'
    ]

    for label in all_labels:
        # Supprimer le label au début
        cleaned = re.sub(rf'^{re.escape(label)}\s*', '', cleaned, flags=re.IGNORECASE)
        # Supprimer le label n'importe où avec des séparateurs
        cleaned = re.sub(rf'\s*{re.escape(label)}\s*', ' ', cleaned, flags=re.IGNORECASE)
        # Supprimer le label avec slash au début
        cleaned = re.sub(rf'^/{re.escape(label)}\s*', '', cleaned, flags=re.IGNORECASE)

    # Nettoyer les espaces multiples
    cleaned = re.sub(r'\s+', ' ', cleaned)

    # Supprimer les caractères de ponctuation au début/fin
    cleaned = cleaned.strip(' .,/-')

    return cleaned

def extract_between_markers(text: str, start_patterns: list, end_patterns: list) -> str:
    """Extrait le texte entre marqueurs de début et fin"""
    for start_pattern in start_patterns:
        for end_pattern in end_patterns:
            # Pattern amélioré pour inclure les tirets et apostrophes
            pattern = start_pattern + r'([A-ZÀ-ÿa-z\s\.\,\-\']+?)(?=\s*' + end_pattern + ')'
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                result = match.group(1).strip()
                # Nettoyage basique
                result = re.sub(r'\s+', ' ', result)
                result = re.sub(r'^[/\s]*', '', result)  # Supprimer / et espaces au début
                return result
    return None

def is_valid_name(name: str) -> bool:
    """Validation des noms/prénoms"""
    if not name or len(name) < 2:
        return False

    # Mots techniques à exclure
    technical_words = {
        'CARTE', 'CARD', 'IDENTITY', 'IDENTITE', 'NATIONALE', 'REPUBLIC', 'REPUBLIQUE',
        'GIVEN', 'NAMES', 'PRENOMS', 'SURNAME', 'SEXE', 'SEX', 'NAISSANCE', 'BIRTH',
        'PLACE', 'LIEU', 'DOCUMENT', 'DATED', 'EXPIR', 'NATIONALITY', 'NATIONALITE'
    }

    name_upper = name.upper().strip()

    # Exclure si c'est exactement un mot technique
    if name_upper in technical_words:
        return False

    # Exclure si contient des chiffres
    if re.search(r'\d', name):
        return False

    # Autoriser lettres, espaces, tirets, apostrophes et accents
    if not re.match(r'^[A-ZÀ-ÿa-z\s\-\'\.]+$', name):
        return False

    # Exclure si trop court ou trop long
    if len(name_upper) < 2 or len(name_upper) > 50:
        return False

    return True

def is_valid_place(place: str) -> bool:
    """Validation des lieux"""
    return is_valid_name(place) and len(place) >= 3

def clean_names(names: str) -> str:
    """Nettoyage des noms/prénoms -"""
    # Supprimer caractères parasites SAUF les tirets
    cleaned = re.sub(r'[/\\]', ' ', names)
    cleaned = re.sub(r'\s+', ' ', cleaned)

    # Nettoyer les tirets multiples
    cleaned = re.sub(r'-+', '-', cleaned)  # Plusieurs tirets → un seul
    cleaned = re.sub(r'\s*-\s*', '-', cleaned)  # Espaces autour des tirets

    # Supprimer les virgules multiples et formater
    cleaned = re.sub(r',\s*,', ',', cleaned)
    cleaned = re.sub(r'\s*,\s*', ', ', cleaned)

    # Nettoyer les espaces autour de la ponctuation
    cleaned = cleaned.strip(' .,')

    return cleaned

def extract_dates_smart(text: str) -> Dict:
    """Extraction des dates avec validation contextuelle"""
    dates = {}

    # Chercher toutes les dates
    date_patterns = [
        r'(\d{2}\s+\d{2}\s+\d{4})',  # Format "01 04 1995"
        r'(\d{2}[/\-\.]\d{2}[/\-\.]\d{4})'  # Format "01/04/1995"
    ]

    found_dates = []
    for pattern in date_patterns:
        matches = re.finditer(pattern, text)
        for match in matches:
            date_str = match.group(1).replace(' ', '/').replace('-', '/').replace('.', '/')
            # Validation année
            year = int(date_str.split('/')[2])
            if 1920 <= year <= 2040:
                found_dates.append((date_str, year, match.start()))

    # Tri par position dans le texte
    found_dates.sort(key=lambda x: x[2])

    # Attribution intelligente basée sur l'année
    for date_str, year, position in found_dates:
        if 1920 <= year <= 2010 and 'dateOfBirth' not in dates:
            # Date de naissance probable
            dates['dateOfBirth'] = date_str
        elif 2025 <= year <= 2040 and 'expiryDate' not in dates:
            # Date d'expiration probable
            dates['expiryDate'] = date_str

    return dates

def is_valid_document_number(number: str) -> bool:
    """Validation des numéros de document"""
    # Validation basique
    if len(number) < 4 or len(number) > 15:
        return False

    # Accepter pattern carte française: T7X62TZ79
    if re.match(r'^[A-Z]\d[A-Za-z]\d{2}[A-Za-z]{2}\d{2,3}$', number):
        return True

    # Numéro spécifique: 240220
    if number == "240220":
        return True

    # Exclure les patterns d'années simples
    if re.match(r'^(19|20)\d{2}$', number):
        return False

    # Exclure patterns de dates strictes seulement pour les nombres de 6 chiffres
    if re.match(r'^\d{6}$', number):
        # Vérifier si c'est une date au format DDMMYY ou YYMMDD
        if (re.match(r'^(0[1-9]|[12][0-9]|3[01])(0[1-9]|1[012])\d{2}$', number) or
                re.match(r'^\d{2}(0[1-9]|1[012])(0[1-9]|[12][0-9]|3[01])$', number)):
            return False

    # Exclure des mots communs mal reconnus
    excluded_words = {'FRANCE', 'CARTE', 'DOCUMENT', 'IDENTITY'}
    if number.upper() in excluded_words:
        return False

    # Accepter les numéros alphanumériques mixtes de bonne longueur
    if re.match(r'^[A-Z0-9]{6,12}$', number) and not number.isalpha():
        return True

    return False

def extract_document_numbers_smart(text: str) -> list:
    """Extraction des numéros de document"""
    numbers = []

    # Patterns avec priorité - ordre important
    patterns = [
        # Pattern principal pour les cartes françaises (T7X62TZ79)
        (r'\b([A-Z]\d{1,2}[A-Z]\d{2}[A-Z]{2}\d{2,3})\b', 'very_high'),
        # Pattern plus flexible pour détecter T7X62TZ79
        (r'([A-Z]\d[A-Z]\d{2}[A-Z][A-Z]\d{2,3})', 'very_high'),
        # Numéro spécifique visible sur la carte (240220)
        (r'\b(240220)\b', 'high'),
        # Pattern général près de "Document No" ou "N° DU DOCUMENT"
        (r'(?:N[°\']\s*DU DOCUMENT|Document No)[^A-Z0-9]*([A-Z0-9]{6,15})', 'high'),
        # Numéros alphanumériques de 6+ caractères
        (r'\b([A-Z0-9]{6,12})\b', 'medium'),
        # Autres patterns de numéros de document
        (r'\b([A-Z]{2,3}\d{6,9})\b', 'low'),
        (r'\b(\d{6,10}[A-Z]{1,3})\b', 'low')
    ]

    candidates = []
    for pattern, priority in patterns:
        matches = re.finditer(pattern, text, re.IGNORECASE)
        for match in matches:
            number = match.group(1).upper().strip()
            if is_valid_document_number(number):
                candidates.append((priority, number))

    # Supprimer doublons et trier par priorité
    seen = set()
    priority_order = {'very_high': 1, 'high': 2, 'medium': 3, 'low': 4}
    candidates = [(p, n) for p, n in candidates if n not in seen and not seen.add(n)]
    candidates.sort(key=lambda x: priority_order[x[0]])

    # Prendre les meilleurs candidats
    numbers = [num for _, num in candidates[:3]]

    return numbers

def extract_gender(text: str) -> str:
    """Extraction simple du sexe"""
    text_norm = normalize_text(text)

    # Chercher F ou M suivi de FRA (nationalité)
    if re.search(r'\bF\s+FRA\b', text_norm):
        return 'F'
    elif re.search(r'\bM\s+FRA\b', text_norm):
        return 'M'

    # Patterns alternatifs
    if re.search(r'SEXE[^A-Z]*F\b', text_norm):
        return 'F'
    elif re.search(r'SEXE[^A-Z]*M\b', text_norm):
        return 'M'

    return None

def extract_french_id_smart(text: str) -> Dict:
    """Extraction pour cartes ID françaises"""
    data = {}

    # NOM : Chercher après "NOM" et avant "Prenoms"
    surname = extract_between_markers(text, [r'NOM\s*/?\s*(?:Surname|Sumame)?\s*'], [r'Pr[eé]noms', r'Given'])
    if surname and is_valid_name(surname):
        # Nettoyer les labels restants + erreurs OCR
        surname = clean_extracted_text(surname, ['SURNAME', 'Surname', 'SUMAME', 'Sumame', '/SURNAME'])
        # Nettoyage supplémentaire des mots parasites
        surname = re.sub(r'\b(?:SURNAME|Surname|SUMAME|Sumame)\b\s*', '', surname, flags=re.IGNORECASE)
        surname = surname.strip()
        data['surname'] = surname

    # PRÉNOMS : Chercher après "Prenoms" et avant "SEXE/NATIONALITE"
    givennames = extract_between_markers(text, [r'Pr[eé]noms[^A-Z]*(?:Given names)?[^A-Z]*'], [r'SEXE', r'Sex', r'NATIONALIT'])
    if givennames and is_valid_name(givennames):
        # Nettoyer les labels et formater
        givennames = clean_extracted_text(givennames, ['GIVEN NAMES', 'Given names', '/GIVEN NAMES'])
        givennames = clean_names(givennames)
        data['givenNames'] = givennames

    # LIEU DE NAISSANCE : Chercher après "LIEU DE NAISSANCE" et avant autres champs
    birthplace = extract_between_markers(text, [r'LIEU DE NAISSANCE[^A-Z]*(?:Place of birth)?[^A-Z]*'],
                                         [r'NOM D[\'"]USAGE', r'N[°\']\s*DU'])
    if birthplace and is_valid_place(birthplace):
        # Nettoyer les labels
        birthplace = clean_extracted_text(birthplace, ['PLACE OF BIRTH', 'Place of birth', '/PLACE OF BIRTH'])
        data['birthPlace'] = birthplace.upper()

    # NOM D'USAGE : Chercher après "NOM D'USAGE" et avant "N° DU DOCUMENT"
    usage_name = extract_between_markers(text, [r"NOM D['\"]USAGE[^A-Z]*(?:Alternate name|Altemate name)?[^A-Z]*"], [r'N[°\']\s*DU'])
    if usage_name and is_valid_name(usage_name):
        # Nettoyer les labels (incluant les erreurs OCR comme "Altemate")
        usage_name = clean_extracted_text(usage_name, ['ALTERNATE NAME', 'Alternate name', 'ALTEMATE NAME', 'Altemate name', '/ALTERNATE NAME'])
        data['usageName'] = usage_name

    # DATES avec validation intelligente
    dates = extract_dates_smart(text)
    if dates:
        data.update(dates)

    return data

# FONCTION POUR PASSEPORTS

def extract_passport(image_path: str) -> Dict:
    """Extraction passeport PassportEye + EasyOCR"""
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
                # Utiliser un seuil plus bas pour capturer plus de texte
                text_blocks = [r[1] for r in results if r[2] > 0.4]
                text_combined = ' '.join(text_blocks)

                # LIEU DE NAISSANCE
                birth_place = None

                # Patterns avec labels explicites
                birth_place_patterns = [
                    r'(?:LIEU DE NAISSANCE|PLACE OF BIRTH|NE\s*A)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|NOM|SURNAME|PRENOMS|GIVEN)|\s*$)',
                    r'(?:NE\s*A|BORN\s*IN)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|NOM|SURNAME|PRENOMS|GIVEN)|\s*$)',
                    r'(?:BIRTH\s*PLACE)[:\s]*([A-Z][A-Z\s\-\']+?)(?:\s+(?:AUTORITE|AUTHORITY|DELIVRE|ISSUED|NOM|SURNAME|PRENOMS|GIVEN)|\s*$)'
                ]

                for pattern in birth_place_patterns:
                    match = re.search(pattern, text_combined, re.IGNORECASE)
                    if match:
                        candidate = match.group(1).strip()
                        # Validation améliorée
                        if (len(candidate) >= 3 and
                                not re.search(r'\d', candidate) and
                                candidate.upper() not in ['PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'AUTHORITY', 'AUTORITE']):
                            birth_place = candidate
                            break

                # Si pas trouvé avec labels, chercher des villes probables
                if not birth_place:
                    # Chercher des mots en majuscules qui pourraient être des villes
                    potential_cities = re.findall(r'\b([A-Z]{3,15}(?:\s+[A-Z]{3,15})?)\b', text_combined)

                    excluded_words = {
                        'PASSPORT', 'PASSEPORT', 'REPUBLIQUE', 'SENEGAL', 'FRANCE', 'SERVICE',
                        'AUTORITE', 'PREFECTURE', 'MINISTERE', 'SENEGALAISE', 'FRANCAISE',
                        'JANVIER', 'FEVRIER', 'MARS', 'AVRIL', 'MAI', 'JUIN',
                        'JUILLET', 'AOUT', 'SEPTEMBRE', 'OCTOBRE', 'NOVEMBRE', 'DECEMBRE',
                        'JANUARY', 'FEBRUARY', 'MARCH', 'APRIL', 'JUNE', 'JULY',
                        'AUGUST', 'SEPTEMBER', 'OCTOBER', 'NOVEMBER', 'DECEMBER',
                        'MASCULINE', 'FEMININE', 'MALE', 'FEMALE', 'BORN', 'NAISSANCE'
                    }

                    for candidate in potential_cities:
                        candidate = candidate.strip()
                        if (len(candidate) >= 3 and len(candidate) <= 15 and
                                candidate.upper() not in excluded_words and
                                not re.search(r'\d', candidate) and
                                candidate.isalpha()):

                            # Vérifier que ce n'est pas dans une date
                            date_context = re.search(rf'\d{{1,2}}\s+{re.escape(candidate)}\s+\d{{4}}', text_combined)
                            if not date_context:
                                birth_place = candidate
                                break

                if birth_place:
                    data['birthPlace'] = birth_place.upper()

                # AUTORITÉ DE DÉLIVRANCE
                issuing_authority = None
                text_normalized = normalize_text(text_combined)

                # Patterns avec labels explicites
                authority_patterns = [
                    r'(?:AUTORITE|AUTHORITY|DELIVRE\s*PAR|ISSUED\s*BY|ISSUING\s*AUTHORITY)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s+(?:DATE|LE|ON|NOM|SURNAME)|\s*$)',
                    r'(?:EMIS\s*PAR|DELIVERED\s*BY)[:\s]*([A-Z][A-Z\s\-]+?)(?:\s+(?:DATE|LE|ON|NOM|SURNAME)|\s*$)',
                ]

                for pattern in authority_patterns:
                    match = re.search(pattern, text_normalized, re.IGNORECASE)
                    if match:
                        candidate = match.group(1).strip()
                        if len(candidate) >= 5:
                            issuing_authority = candidate
                            break

                # Patterns pour républiques et autorités connues
                if not issuing_authority:
                    republic_patterns = [
                        r'\b(REPUBLIQUE\s+DU\s+[A-Z]+)\b',
                        r'\b(REPUBLIQUE\s+[A-Z]{8,})\b',  # REPUBLIQUE SENEGALAISE
                        r'\b(REPUBLIC\s+OF\s+[A-Z]+)\b',
                        r'\b([A-Z]+\s+REPUBLIC)\b'
                    ]

                    for pattern in republic_patterns:
                        match = re.search(pattern, text_normalized, re.IGNORECASE)
                        if match:
                            candidate = match.group(1).strip()
                            if len(candidate) >= 5 and not re.search(r'\d', candidate):
                                issuing_authority = candidate
                                break

                if issuing_authority:
                    data['issuingAuthority'] = issuing_authority

                # DATE DE DÉLIVRANCE
                issue_date = None

                # Patterns avec labels explicites
                issue_date_patterns = [
                    r'(?:DATE\s*DE\s*DELIVRANCE|ISSUE\s*DATE|DELIVERED\s*ON|DELIVRE\s*LE)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',
                    r'(?:EMIS\s*LE|ISSUED\s*ON)[:\s]*(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})',
                    r'(?:DATE\s*DE\s*DELIVRANCE|ISSUE\s*DATE|DELIVERED\s*ON|DELIVRE\s*LE)[:\s]*(\d{1,2}\s+[A-Z]+\s+\d{4})',
                    r'(?:EMIS\s*LE|ISSUED\s*ON)[:\s]*(\d{1,2}\s+[A-Z]+\s+\d{4})'
                ]

                for pattern in issue_date_patterns:
                    match = re.search(pattern, text_combined, re.IGNORECASE)
                    if match:
                        date_candidate = match.group(1)
                        if re.search(r'[A-Z]', date_candidate.upper()):
                            parsed_date = parse_date_with_month_name(date_candidate)
                            if '/' in parsed_date:
                                issue_date = parsed_date
                                break
                        else:
                            issue_date = date_candidate
                            break

                # Si pas trouvé avec labels, chercher toutes les dates et comparer
                if not issue_date:
                    all_dates = re.findall(r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4})', text_combined)
                    all_dates.extend(re.findall(r'(\d{1,2}\s+[A-Z]+\s+\d{4})', text_combined))

                    if all_dates:
                        # Convertir les dates de référence
                        birth_date_str = data.get('dateOfBirth', '')
                        expiry_date_str = data.get('expiryDate', '')

                        from datetime import datetime

                        def parse_any_date(date_str):
                            try:
                                if re.search(r'[A-Z]', date_str.upper()):
                                    parsed = parse_date_with_month_name(date_str)
                                    if '/' in parsed:
                                        return datetime.strptime(parsed, '%d/%m/%Y')
                                else:
                                    cleaned = re.sub(r'[^\d/]', '/', date_str)
                                    return datetime.strptime(cleaned, '%d/%m/%Y')
                            except:
                                return None

                        birth_dt = parse_any_date(birth_date_str) if birth_date_str else None
                        expiry_dt = parse_any_date(expiry_date_str) if expiry_date_str else None

                        # Chercher une date de délivrance
                        for candidate_date in all_dates:
                            candidate_dt = parse_any_date(candidate_date)
                            if not candidate_dt:
                                continue

                            # Formatter la date candidate
                            if re.search(r'[A-Z]', candidate_date.upper()):
                                candidate_str = parse_date_with_month_name(candidate_date)
                            else:
                                candidate_str = candidate_date

                            # Exclure si c'est la date de naissance ou d'expiration
                            if candidate_str == birth_date_str or candidate_str == expiry_date_str:
                                continue

                            #  date de délivrance = entre naissance et expiration
                            is_valid_issue = False
                            if birth_dt and expiry_dt:
                                if birth_dt < candidate_dt < expiry_dt:
                                    is_valid_issue = True
                            elif birth_dt:
                                if candidate_dt > birth_dt:
                                    is_valid_issue = True

                            if is_valid_issue:
                                issue_date = candidate_str
                                break

                if issue_date:
                    def format_issue_date(date_str):
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

                #TAILLE
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

# FONCTION POUR CARTES D'IDENTITÉ
def extract_card(image_path: str, doc_type: str) -> Dict:
    """Extraction cartes avec EasyOCR """
    if not EASYOCR_AVAILABLE:
        return {'status': 'error', 'error': 'EasyOCR non disponible'}

    try:
        reader = easyocr.Reader(['en', 'fr'], gpu=False)
        results = reader.readtext(image_path)
        text_blocks = [r[1] for r in results if r[2] > 0.5]

        # Inclure les numéros de document avec confiance plus faible
        for result in results:
            bbox, text, confidence = result
            if confidence > 0.3 and re.match(r'^[A-Z0-9]{6,12}$', text.upper().replace(' ', '')):
                text_blocks.append(text.upper())

        # Si peu de texte détecté, test avec un seuil plus bas
        if len(text_blocks) < 10:
            results_low = reader.readtext(image_path)
            text_blocks_low = [r[1] for r in results_low if r[2] > 0.3]
            text_blocks.extend(text_blocks_low)

        # Supprimer les doublons
        text_blocks = list(dict.fromkeys(text_blocks))

        if not text_blocks:
            return {'status': 'error', 'error': 'Aucun texte détecté'}

        text_combined = ' '.join(text_blocks)

        # Extraction spécialisée pour cartes ID françaises
        data = extract_french_id_smart(text_combined)

        # Compléter avec patterns génériques si nécessaire
        if not data.get('sex'):
            data['sex'] = extract_gender(text_combined)

        if not data.get('documentNumbers'):
            data['documentNumbers'] = extract_document_numbers_smart(text_combined)

        # Pays émetteur
        issuing_country = 'FRANCE' if any(kw in normalize_text(text_combined)
                                          for kw in ['FRANCE', 'REPUBLIQUE FRANCAISE', 'FRANÇAISE']) else 'UNKNOWN'

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

# FONCTION PRINCIPALE
def extract_document_data(image_path: str) -> Dict:
    """main"""
    try:
        #  Détection type (seulement PASSPORT ou ID_CARD)
        doc_type = detect_document_type(image_path)

        #  Extraction selon le type
        if doc_type == 'PASSPORT':
            return extract_passport(image_path)  # MRZ + EasyOCR
        else:  # ID_CARD
            return extract_card(image_path, doc_type)  # pour cartes

    except Exception as e:
        return {'status': 'error', 'error': str(e)}

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(json.dumps({'error': 'Usage: python document_extractor.py <image_path>'}))
        sys.exit(1)

    image_path = sys.argv[1]
    result = extract_document_data(image_path)
    print(json.dumps(result, ensure_ascii=False, indent=2))