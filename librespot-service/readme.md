To run

# Start the Go service first (one-time OAuth will open in browser)
cd librespot-service                                                                                                                                                                                     
PKG_CONFIG_PATH="/opt/homebrew/lib/pkgconfig" go run .



 pm2 start "go run ." --name librespot-service  


   pm2 logs librespot-service       


                                                          
  Useful pm2 commands:                                                                                                                                                                                     
  pm2 list                        # see all running services
  pm2 logs librespot-service      # tail logs                                                                                                                                                              
  pm2 restart librespot-service   # restart            
  pm2 stop librespot-service      # stop                                                                                                                                                                   
                                        
  ▎ Note: go run . recompiles on every start. If you want faster restarts, first build a binary then run that:                                                                                             
  ▎ go build -o librespot-service .                                                                                                                                                                        
  ▎ pm2 start ./librespot-service --name librespot-service
