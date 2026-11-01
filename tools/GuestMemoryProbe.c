// Standalone allocation/free workload. Does not touch any Wine process.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static long resident_kb(void) {
    FILE* input=fopen("/proc/self/status","r");if(!input)return -1;
    char line[256];long kb=-1;
    while(fgets(line,sizeof(line),input))if(sscanf(line,"VmRSS: %ld kB",&kb)==1)break;
    fclose(input);return kb;
}
int main(void) {
    void* chunks[4096]={0};long before=resident_kb();
    for(int n=0;n<4096;n++) {
        chunks[n]=malloc(16*1024);if(!chunks[n])return 2;
        volatile unsigned char* bytes=chunks[n];
        for(int p=0;p<16*1024;p+=4096)bytes[p]=1;
    }
    long active=resident_kb();
    for(int n=0;n<4096;n++)free(chunks[n]);
    usleep(100000);
    printf("aggressive=%s before_kb=%ld allocated_kb=%ld after_free_kb=%ld\n",
           getenv("WINXCLIPSE_RAM_AGGRESSIVE")?getenv("WINXCLIPSE_RAM_AGGRESSIVE"):"0",before,active,resident_kb());
    return 0;
}
