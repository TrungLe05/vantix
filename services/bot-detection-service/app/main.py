from fastapi import FastAPI, APIRouter

app = FastAPI(
    title="bot-detection-service",
    docs_url="/api/bot-detection/docs",
    redoc_url="/api/bot-detection/redoc",
    openapi_url="/api/bot-detection/openapi.json",
)

api_router = APIRouter(prefix="/api/bot-detection")


@api_router.get("/health")
def health_check():
    return {"status": "UP"}

app.include_router(api_router)