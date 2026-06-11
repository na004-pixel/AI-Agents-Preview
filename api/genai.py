import httpx
from typing import Any, List

class DomainError(Exception):
    def __init__(self, message, error_kind, details):
        super().__init__(message)
        self.error_kind = error_kind
        self.details = details

class CanonicalResultShape:
    @staticmethod
    def success(data: Any, metadata: Any = None) -> Any:
        return {"success": True, "data": data, "metadata": metadata}
    
    @staticmethod
    def failure(error: Any, metadata: Any = None) -> Any:
        return {"success": False, "error": error, "metadata": metadata}

class GenaiSDK:
    HTTP_CLIENT = httpx.Client(timeout=10.0)

    @staticmethod
    def callModelOnce(modelName: str, contents: List[Any], config: Any, serviceAccountJson: str) -> Any:
        try:
            bearerToken = "mock_token_for_showcase"
            jsonRequest = "{}"

            url = f"https://aiplatform.googleapis.com/v1/projects/showcase-project/locations/global/{modelName}:generateContent"

            headers = {
                "Authorization": f"Bearer {bearerToken}",
                "Content-Type": "application/json"
            }

            response = GenaiSDK.HTTP_CLIENT.post(url, headers=headers, content=jsonRequest, timeout=900.0)

            if response.status_code != 200:
                return CanonicalResultShape.failure(
                    DomainError("Gemini API Error", {"INTERNAL"}, {"statusCode": response.status_code})
                )

            genaiResp = response.json()
            return CanonicalResultShape.success(genaiResp)

        except Exception as e:
            return CanonicalResultShape.failure(
                DomainError(f"API Call Failed: {str(e)}", {"INTERNAL"}, {})
            )

    @staticmethod
    def text(content: Any) -> str:
        sb = []
        if content is not None:
            parts_attr = getattr(content, 'parts', None)
            if parts_attr is not None:
                parts = parts_attr() if callable(parts_attr) else parts_attr
                if parts is not None:
                    for p in parts:
                        t_attr = getattr(p, 'text', None)
                        if t_attr is not None:
                            t = t_attr() if callable(t_attr) else t_attr
                            if t is not None:
                                if len(sb) > 0:
                                    sb.append("\n")
                                sb.append(t)
        return "".join(sb)
