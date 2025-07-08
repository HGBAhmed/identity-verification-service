import sys
import json
import re
import unicodedata
from typing import Dict, Optional

# Imports + gestion d'erreurs
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

        # Extraction EasyOCR pour données non-MRZ
        if EASYOCR_AVAILABLE:
            try:
                reader = easyocr.Reader(['en', 'fr'], gpu=False)
                results = reader.readtext(image_path)
                text_blocks = [r[1] for r in results if r[2] > 0.5]
                text_combined = ' '.join(text_blocks)

                # Lieu de naissance - Approche générique avec filtrage
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
                            'JUILJUL', 'JUIL', 'SEPT', 'JANV', 'FEVR', 'MARS',

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

                # Taille
                height_match = re.search(r'(\d{1}[,\.]\d{2})\s*[mM]', text_combined)
                if height_match:
                    height = height_match.group(1).replace(',', '.')
                    data['height'] = f"{height}m"

            except Exception:
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
    """Extraction cartes avec EasyOCR """
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

        # Extraction noms - Approche séquentielle pour cartes étudiantes
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

        # Dates avec distinction de type
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

        #  Numéros de document - Extraction améliorée
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

        #  Sexe
        text_norm = normalize_text(text_combined)
        if re.search(r'\b(M|MALE|MASCULIN)\b', text_norm):
            data['sex'] = 'M'
        elif re.search(r'\b(F|FEMALE|FEMININ)\b', text_norm):
            data['sex'] = 'F'

        #  Pays
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
        #  Détection type
        doc_type = detect_document_type(image_path)

        #  Extraction selon le type
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